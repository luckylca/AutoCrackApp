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

type JavaFieldMetadata = {
  name: string;
  type: string;
  modifiers: string;
  static: boolean;
  final: boolean;
  writable: boolean;
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

function javaFieldMetadata(field: any): JavaFieldMetadata {
  const modifiers = Number(field.getModifiers());
  const type = String(field.getType().getName());
  const isStatic = (modifiers & 0x0008) !== 0;
  const isFinal = (modifiers & 0x0010) !== 0;
  return {
    name: String(field.getName()).slice(0, 512),
    type: type.slice(0, 512),
    modifiers: String(field.toString()).split(' ').slice(0, -1).join(' ').slice(0, 512),
    static: isStatic,
    final: isFinal,
    writable: !isStatic && !isFinal && isSupportedJavaFieldType(type),
  };
}

function isSupportedJavaFieldType(type: string): boolean {
  return new Set([
    'boolean', 'byte', 'short', 'int', 'long', 'float', 'double', 'char',
    'java.lang.Boolean', 'java.lang.Byte', 'java.lang.Short', 'java.lang.Integer',
    'java.lang.Long', 'java.lang.Float', 'java.lang.Double', 'java.lang.Character',
    'java.lang.String',
  ]).has(type);
}

function previewJavaValue(value: any): any {
  if (value === null || value === undefined) return null;
  const className = String(value.$className ?? '');
  if (className === 'java.lang.String') {
    return { type: className, value: String(value).slice(0, 512) };
  }
  if (new Set([
    'java.lang.Boolean', 'java.lang.Byte', 'java.lang.Short', 'java.lang.Integer',
    'java.lang.Long', 'java.lang.Float', 'java.lang.Double', 'java.lang.Character',
  ]).has(className)) {
    return { type: className, value: String(value).slice(0, 128) };
  }
  return { type: className || 'java.lang.Object' };
}

function previewJavaInstance(instance: any, className: string, maxFields: number): any {
  const fields: any[] = [];
  const declared = instance.getClass().getDeclaredFields();
  for (let index = 0; index < declared.length && fields.length < maxFields; index += 1) {
    const field = declared[index];
    const metadata = javaFieldMetadata(field);
    if (metadata.static) continue;
    try {
      field.setAccessible(true);
      fields.push({ ...metadata, value: previewJavaValue(field.get(instance)) });
    } catch (error) {
      fields.push({ ...metadata, error: String(error).slice(0, 512) });
    }
  }
  return { className, fields };
}

function requireFiniteNumber(value: any, type: string, minimum: number, maximum: number): number {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < minimum || value > maximum) {
    throw new Error(`${type} value must be a finite number in ${minimum}..${maximum}`);
  }
  return value;
}

function requireInteger(value: any, type: string, minimum: number, maximum: number): number {
  const number = requireFiniteNumber(value, type, minimum, maximum);
  if (!Number.isInteger(number)) throw new Error(`${type} value must be an integer`);
  return number;
}

function coerceJavaFieldValue(type: string, value: any): any {
  if (value === null) {
    if (!type.includes('.')) throw new Error(`primitive ${type} cannot be set to null`);
    return null;
  }
  switch (type) {
    case 'boolean':
    case 'java.lang.Boolean':
      if (typeof value !== 'boolean') throw new Error(`${type} value must be boolean`);
      return (Java.use('java.lang.Boolean') as any).valueOf(value);
    case 'byte':
    case 'java.lang.Byte':
      return (Java.use('java.lang.Byte') as any).valueOf(requireInteger(value, type, -128, 127));
    case 'short':
    case 'java.lang.Short':
      return (Java.use('java.lang.Short') as any).valueOf(requireInteger(value, type, -32768, 32767));
    case 'int':
    case 'java.lang.Integer':
      return (Java.use('java.lang.Integer') as any).valueOf(requireInteger(value, type, -2147483648, 2147483647));
    case 'long':
    case 'java.lang.Long':
      return (Java.use('java.lang.Long') as any).valueOf(requireInteger(value, type, Number.MIN_SAFE_INTEGER, Number.MAX_SAFE_INTEGER));
    case 'float':
    case 'java.lang.Float':
      return (Java.use('java.lang.Float') as any).valueOf(requireFiniteNumber(value, type, -3.4028235e38, 3.4028235e38));
    case 'double':
    case 'java.lang.Double':
      return (Java.use('java.lang.Double') as any).valueOf(requireFiniteNumber(value, type, -Number.MAX_VALUE, Number.MAX_VALUE));
    case 'char':
    case 'java.lang.Character':
      if (typeof value !== 'string' || Array.from(value).length !== 1) throw new Error(`${type} value must be one character`);
      return (Java.use('java.lang.Character') as any).valueOf(value);
    case 'java.lang.String':
      if (typeof value !== 'string' || value.length > 4096) throw new Error('String value must contain at most 4096 characters');
      return (Java.use('java.lang.String') as any).$new(value);
    default:
      throw new Error(`field type is not writable through the bounded agent: ${type}`);
  }
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
      agentVersion: '1.2.0',
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

  javafields(className: string, maxCount: number) {
    if (!Java.available) return Promise.resolve({ available: false, className, fields: [] as JavaFieldMetadata[] });
    const requested = normalizeText(className, 512);
    if (requested.length === 0) throw new Error('className must not be empty');
    const limit = clampInt(maxCount, 1, 512);
    return new Promise((resolve, reject) => {
      Java.perform(() => {
        try {
          const klass = Java.use(requested);
          const declared = klass.class.getDeclaredFields();
          const fields: JavaFieldMetadata[] = [];
          for (let index = 0; index < declared.length && fields.length < limit; index += 1) {
            fields.push(javaFieldMetadata(declared[index]));
          }
          resolve({ available: true, className: requested, fields });
        } catch (error) {
          reject(error);
        }
      });
    });
  },

  javainstances(className: string, maxCount: number, maxFields: number) {
    if (!Java.available) return Promise.resolve({ available: false, className, instances: [] as any[] });
    const requested = normalizeText(className, 512);
    if (requested.length === 0) throw new Error('className must not be empty');
    const limit = clampInt(maxCount, 1, 64);
    const fieldLimit = clampInt(maxFields, 0, 32);
    return new Promise((resolve, reject) => {
      Java.perform(() => {
        const instances: any[] = [];
        try {
          Java.choose(requested, {
            onMatch(instance) {
              try {
                instances.push({ index: instances.length, ...previewJavaInstance(instance, requested, fieldLimit) });
                if (instances.length >= limit) return 'stop';
                return undefined;
              } catch (error) {
                reject(error);
                return 'stop';
              }
            },
            onComplete() {
              resolve({ available: true, className: requested, instances });
            },
          });
        } catch (error) {
          reject(error);
        }
      });
    });
  },

  javafieldwrite(className: string, fieldName: string, instanceIndex: number, value: any) {
    if (!Java.available) return Promise.resolve({ available: false, className, fieldName, written: false });
    const requested = normalizeText(className, 512);
    const requestedField = normalizeText(fieldName, 512);
    const selectedIndex = clampInt(instanceIndex, 0, 63);
    if (requested.length === 0) throw new Error('className must not be empty');
    if (requestedField.length === 0) throw new Error('fieldName must not be empty');
    return new Promise((resolve, reject) => {
      Java.perform(() => {
        let currentIndex = 0;
        let matched = false;
        try {
          Java.choose(requested, {
            onMatch(instance) {
              try {
                if (currentIndex !== selectedIndex) {
                  currentIndex += 1;
                  return undefined;
                }
                matched = true;
                const declared = instance.getClass().getDeclaredFields();
                let selectedField: any = null;
                for (let index = 0; index < declared.length; index += 1) {
                  if (String(declared[index].getName()) === requestedField) {
                    selectedField = declared[index];
                    break;
                  }
                }
                if (selectedField === null) throw new Error(`declared field was not found: ${requestedField}`);
                const metadata = javaFieldMetadata(selectedField);
                if (metadata.static) throw new Error('static field writes are not supported');
                if (metadata.final) throw new Error('final field writes are not supported');
                selectedField.setAccessible(true);
                const before = previewJavaValue(selectedField.get(instance));
                selectedField.set(instance, coerceJavaFieldValue(metadata.type, value));
                const after = previewJavaValue(selectedField.get(instance));
                resolve({
                  available: true,
                  className: requested,
                  fieldName: requestedField,
                  instanceIndex: selectedIndex,
                  field: metadata,
                  before,
                  after,
                  written: true,
                });
                return 'stop';
              } catch (error) {
                reject(error);
                return 'stop';
              }
            },
            onComplete() {
              if (!matched) reject(new Error(`instance index was not found: ${selectedIndex}`));
            },
          });
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
