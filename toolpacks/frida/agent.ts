import Java from 'frida-java-bridge';

type TraceEvent = {
  sequence: number;
  timestampMs: number;
  threadId: number;
  target: string;
  returnAddress: string;
  backtrace: string[];
};

type TlsTraceEvent = {
  sequence: number;
  timestampMs: number;
  threadId: number;
  direction: 'read' | 'write';
  className: string;
  method: string;
  byteCount: number;
  capturedBytes: number;
  previewHex: string;
  previewText: string;
};

let traceListener: InvocationListener | null = null;
let traceEvents: TraceEvent[] = [];
let traceLimit = 64;
let traceTarget: NativePointer | null = null;
let traceSequence = 0;

let tlsTraceListeners: Array<{ overload: any; original: any }> = [];
let tlsTraceEvents: TlsTraceEvent[] = [];
let tlsTraceLimit = 64;
let tlsPreviewLimit = 256;
let tlsTraceSequence = 0;

function clampInt(value: number, minimum: number, maximum: number): number {
  if (!Number.isFinite(value)) return minimum;
  return Math.min(maximum, Math.max(minimum, Math.trunc(value)));
}

function normalizeText(value: string, maximum: number): string {
  return String(value ?? '').trim().slice(0, maximum);
}

function clearTrace(): void {
  if (traceListener !== null) {
    traceListener.detach();
    traceListener = null;
  }
  traceEvents = [];
  traceTarget = null;
  traceSequence = 0;
}

function clearTlsTrace(): void {
  for (const entry of tlsTraceListeners) {
    try {
      entry.overload.implementation = entry.original;
    } catch (_) {
      // Best-effort cleanup only; the script unload still removes all hooks.
    }
  }
  tlsTraceListeners = [];
  tlsTraceEvents = [];
  tlsTraceSequence = 0;
}

function bytePreview(bytes: any, offset: number, count: number): { hex: string; text: string; captured: number } {
  const start = Math.max(0, Math.trunc(offset));
  const requested = Math.max(0, Math.trunc(count));
  const available = Math.max(0, Number(bytes?.length ?? 0) - start);
  const captured = Math.min(requested, available, tlsPreviewLimit);
  const hex: string[] = [];
  const text: string[] = [];
  for (let index = 0; index < captured; index += 1) {
    const value = Number(bytes[start + index]) & 0xff;
    hex.push(value.toString(16).padStart(2, '0'));
    text.push(value >= 0x20 && value <= 0x7e ? String.fromCharCode(value) : '.');
  }
  return { hex: hex.join(''), text: text.join(''), captured };
}

function appendTlsEvent(
  direction: 'read' | 'write',
  className: string,
  method: string,
  bytes: any,
  offset: number,
  count: number,
): void {
  if (tlsTraceEvents.length >= tlsTraceLimit || count <= 0) return;
  const preview = bytePreview(bytes, offset, count);
  tlsTraceEvents.push({
    sequence: ++tlsTraceSequence,
    timestampMs: Date.now(),
    threadId: Process.getCurrentThreadId(),
    direction,
    className,
    method,
    byteCount: Math.max(0, Math.trunc(count)),
    capturedBytes: preview.captured,
    previewHex: preview.hex,
    previewText: preview.text,
  });
}

function installConscryptTlsHooks(): Promise<{ available: boolean; hookCount: number; classes: string[] }> {
  if (!Java.available) return Promise.resolve({ available: false, hookCount: 0, classes: [] });
  return new Promise((resolve, reject) => {
    Java.perform(() => {
      try {
        const hookedClasses: string[] = [];
        const candidates = ['com.android.org.conscrypt.NativeCrypto', 'org.conscrypt.NativeCrypto'];
        for (const className of candidates) {
          let klass: any;
          try {
            klass = Java.use(className);
          } catch (_) {
            continue;
          }
          let classHooked = false;
          for (const methodName of ['SSL_write', 'SSL_read']) {
            const method = klass[methodName];
            if (method === undefined) continue;
            for (const overload of method.overloads as any[]) {
              const argumentTypes = (overload.argumentTypes as any[]).map((type) => String(type.className ?? type.name ?? type));
              const byteIndex = argumentTypes.findIndex((type) => type === '[B');
              if (byteIndex < 0 || byteIndex + 2 >= argumentTypes.length) continue;
              if (argumentTypes[byteIndex + 1] !== 'int' || argumentTypes[byteIndex + 2] !== 'int') continue;
              const original = overload.implementation;
              const direction: 'read' | 'write' = methodName === 'SSL_read' ? 'read' : 'write';
              overload.implementation = function (...args: any[]) {
                const offset = Number(args[byteIndex + 1]);
                const requested = Number(args[byteIndex + 2]);
                if (direction === 'write') {
                  appendTlsEvent(direction, className, methodName, args[byteIndex], offset, requested);
                }
                const result = overload.call(this, ...args);
                if (direction === 'read') {
                  appendTlsEvent(direction, className, methodName, args[byteIndex], offset, Number(result));
                }
                return result;
              };
              tlsTraceListeners.push({ overload, original });
              classHooked = true;
            }
          }
          if (classHooked) hookedClasses.push(className);
        }
        resolve({ available: true, hookCount: tlsTraceListeners.length, classes: hookedClasses });
      } catch (error) {
        reject(error);
      }
    });
  });
}

rpc.exports = {
  ping() {
    return {
      agentVersion: '1.1.0',
      fridaVersion: Frida.version,
      pid: Process.id,
      arch: Process.arch,
      platform: Process.platform,
      javaAvailable: Java.available,
    };
  },

  modules(maxCount: number) {
    const limit = clampInt(maxCount, 1, 512);
    return Process.enumerateModules().slice(0, limit).map((module) => ({
      name: module.name,
      base: module.base.toString(),
      size: module.size,
      path: module.path,
    }));
  },

  exports(moduleName: string, query: string, maxCount: number) {
    const name = normalizeText(moduleName, 256);
    if (name.length === 0) throw new Error('moduleName must not be empty');
    const needle = normalizeText(query, 256).toLowerCase();
    const limit = clampInt(maxCount, 1, 512);
    const module = Process.getModuleByName(name);
    const matches: Array<{ type: string; name: string; address: string }> = [];
    for (const entry of module.enumerateExports()) {
      if (needle.length !== 0 && !entry.name.toLowerCase().includes(needle)) continue;
      matches.push({ type: entry.type, name: entry.name, address: entry.address.toString() });
      if (matches.length >= limit) break;
    }
    return { module: module.name, base: module.base.toString(), exports: matches };
  },

  javaclasses(query: string, maxCount: number) {
    if (!Java.available) return Promise.resolve({ available: false, classes: [] as string[] });
    const needle = normalizeText(query, 256).toLowerCase();
    const limit = clampInt(maxCount, 1, 512);
    return new Promise((resolve, reject) => {
      Java.perform(() => {
        const matches: string[] = [];
        try {
          Java.enumerateLoadedClasses({
            onMatch(name) {
              if (matches.length >= limit) return;
              if (needle.length === 0 || name.toLowerCase().includes(needle)) matches.push(name);
            },
            onComplete() {
              resolve({ available: true, classes: matches });
            },
          });
        } catch (error) {
          reject(error);
        }
      });
    });
  },

  javamethods(className: string, maxCount: number) {
    if (!Java.available) return Promise.resolve({ available: false, className, methods: [] as string[] });
    const requested = normalizeText(className, 512);
    if (requested.length === 0) throw new Error('className must not be empty');
    const limit = clampInt(maxCount, 1, 512);
    return new Promise((resolve, reject) => {
      Java.perform(() => {
        try {
          const methods: string[] = [];
          const groups = Java.enumerateMethods(`${requested}!*/s`);
          for (const group of groups) {
            for (const klass of group.classes) {
              if (klass.name !== requested) continue;
              for (const method of klass.methods) {
                methods.push(String(method).slice(0, 2048));
                if (methods.length >= limit) {
                  resolve({ available: true, className: requested, methods });
                  return;
                }
              }
            }
          }
          resolve({ available: true, className: requested, methods });
        } catch (error) {
          reject(error);
        }
      });
    });
  },

  netstack(maxCount: number) {
    if (!Java.available) return Promise.resolve({ available: false, classes: [] as string[] });
    const limit = clampInt(maxCount, 1, 128);
    const needles = [
      'okhttp3.',
      'java.net.HttpURLConnection',
      'javax.net.ssl.',
      'com.android.org.conscrypt.',
      'org.conscrypt.',
      'org.chromium.net.',
    ];
    return new Promise((resolve, reject) => {
      Java.perform(() => {
        try {
          const matches: string[] = [];
          Java.enumerateLoadedClasses({
            onMatch(name) {
              if (matches.length >= limit) return;
              if (needles.some((needle) => name.startsWith(needle) || name === needle)) matches.push(name);
            },
            onComplete() {
              resolve({ available: true, classes: matches });
            },
          });
        } catch (error) {
          reject(error);
        }
      });
    });
  },

  networkhints(maxCount: number) {
    if (!Java.available) return Promise.resolve({ available: false, hints: [] as any[] });
    const limit = clampInt(maxCount, 1, 128);
    const rules = [
      { id: 'okhttp', needles: ['okhttp3.'], meaning: 'OkHttp stack loaded' },
      { id: 'okhttp_pinner', needles: ['okhttp3.CertificatePinner'], meaning: 'OkHttp certificate pinning class loaded' },
      { id: 'urlconnection', needles: ['java.net.HttpURLConnection', 'javax.net.ssl.HttpsURLConnection'], meaning: 'JDK URLConnection stack loaded' },
      { id: 'conscrypt', needles: ['com.android.org.conscrypt.', 'org.conscrypt.'], meaning: 'Conscrypt TLS stack loaded' },
      { id: 'cronet', needles: ['org.chromium.net.', 'cronet'], meaning: 'Cronet / Chromium network stack loaded' },
      { id: 'trust_manager', needles: ['X509TrustManager', 'TrustManager'], meaning: 'Trust manager related class loaded' },
    ];
    return new Promise((resolve, reject) => {
      Java.perform(() => {
        try {
          const classNames: string[] = [];
          Java.enumerateLoadedClasses({
            onMatch(name) {
              if (classNames.length >= 4096) return;
              classNames.push(name);
            },
            onComplete() {
              const hints: any[] = [];
              for (const rule of rules) {
                const evidence = classNames.filter((name) => rule.needles.some((needle) => name.includes(needle))).slice(0, 12);
                if (evidence.length > 0) hints.push({ id: rule.id, meaning: rule.meaning, evidence });
                if (hints.length >= limit) break;
              }
              resolve({ available: true, hintCount: hints.length, hints });
            },
          });
        } catch (error) {
          reject(error);
        }
      });
    });
  },

  tlstracestart(maxEvents: number, maxBytesPerEvent: number) {
    clearTlsTrace();
    tlsTraceLimit = clampInt(maxEvents, 1, 128);
    tlsPreviewLimit = clampInt(maxBytesPerEvent, 16, 1024);
    return installConscryptTlsHooks();
  },

  tlstracestop() {
    for (const entry of tlsTraceListeners) {
      try {
        entry.overload.implementation = entry.original;
      } catch (_) {
        // Script unload remains the final cleanup boundary.
      }
    }
    const result = {
      eventCount: tlsTraceEvents.length,
      maxEvents: tlsTraceLimit,
      maxBytesPerEvent: tlsPreviewLimit,
      events: tlsTraceEvents.slice(0, tlsTraceLimit),
    };
    tlsTraceListeners = [];
    tlsTraceEvents = [];
    tlsTraceSequence = 0;
    return result;
  },

  tracestart(moduleName: string, offsetText: string, maxEvents: number) {
    clearTrace();
    const name = normalizeText(moduleName, 256);
    if (name.length === 0) throw new Error('moduleName must not be empty');
    const module = Process.getModuleByName(name);
    const offset = ptr(normalizeText(offsetText, 32));
    if (offset.compare(ptr('0')) < 0 || offset.compare(ptr(module.size)) >= 0) {
      throw new Error('offset must be inside the selected module');
    }
    const target = module.base.add(offset);
    const targetRange = Process.findRangeByAddress(target);
    if (targetRange === null || !targetRange.protection.includes('x')) {
      throw new Error('trace target must resolve to an executable memory range');
    }
    traceLimit = clampInt(maxEvents, 1, 128);
    traceTarget = target;
    traceListener = Interceptor.attach(target, {
      onEnter() {
        if (traceEvents.length >= traceLimit) return;
        let frames: string[] = [];
        try {
          frames = Thread.backtrace(this.context, Backtracer.ACCURATE)
            .slice(0, 12)
            .map((address) => DebugSymbol.fromAddress(address).toString().slice(0, 512));
        } catch (_) {
          frames = [];
        }
        traceEvents.push({
          sequence: ++traceSequence,
          timestampMs: Date.now(),
          threadId: Process.getCurrentThreadId(),
          target: target.toString(),
          returnAddress: this.returnAddress.toString(),
          backtrace: frames,
        });
      },
    });
    return { module: module.name, base: module.base.toString(), offset: offset.toString(), target: target.toString(), maxEvents: traceLimit };
  },

  tracestop() {
    if (traceListener !== null) {
      traceListener.detach();
      traceListener = null;
    }
    const result = {
      target: traceTarget?.toString() ?? null,
      eventCount: traceEvents.length,
      events: traceEvents.slice(0, traceLimit),
    };
    traceEvents = [];
    traceTarget = null;
    traceSequence = 0;
    return result;
  },
};
