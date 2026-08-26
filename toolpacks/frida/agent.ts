import Java from 'frida-java-bridge';

type TraceEvent = {
  sequence: number;
  timestampMs: number;
  threadId: number;
  target: string;
  returnAddress: string;
  backtrace: string[];
};

let traceListener: InvocationListener | null = null;
let traceEvents: TraceEvent[] = [];
let traceLimit = 64;
let traceTarget: NativePointer | null = null;
let traceSequence = 0;

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

rpc.exports = {
  ping() {
    return {
      agentVersion: '1.0.0',
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
