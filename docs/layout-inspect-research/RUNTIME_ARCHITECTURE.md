# Shared AutoCrack Runtime Architecture

```text
Agent
  -> exec_bash
    -> ui-inspect | runtime-inspect | memory-dump | runtime-control | simplehook
      -> shared autocrack-runtime-client + android-shell
        -> AutoCrack Runtime companion provider
          -> explicit request/result channel
            -> one Xposed entry / one bootstrap in target process
              -> RuntimeDispatcher
                -> WindowRegistry / ActivityRegistry / ClassLoaderRegistry
                -> ObjectRegistry / ViewCreationTracker / WebViewRegistry
                -> HookEngine / MemoryStrategies / ControlActions
```

## Invariants

- Exactly one AutoCrack Xposed module is loaded for all five CLIs.
- Bootstrap is idempotent per process/PID.
- ClassLoader, window, Activity and View creation hooks are installed once.
- Every request names a capability and carries package/process/session/id/nonce.
- UI and WebView work runs on the main looper; long reflection and serialization
  work runs on a bounded worker after a main-thread snapshot.
- Large byte results are written under `/workspace/artifacts` and stdout returns
  metadata, hash, size and path.

## ObjectRegistry

Handles are opaque `obj_<random>` values. Entries contain a weak reference by
default, optional strong pin, type, package, process, PID, session, created and
last-access times, and expiry. The registry enforces TTL, max-count LRU cleanup,
pin limits, stale detection, release, session clear and process binding.

A handle is rejected if the current package/process/PID/session does not match.
This is what makes `ui-inspect at -> runtime-inspect object` safe and real: both
commands address the same injected registry rather than serializing a fake
pointer or re-finding a View by hash.

## Capability response

Every dispatcher response has `ok`, runtime identity, capability, strategy,
support status and bounded result data. Unsupported operations return a stable
error object and strategy list; they never return fabricated empty success.
