# AutoCrack jnitrace compatibility patch

Upstream: `chame1eon/jnitrace` / PyPI `jnitrace==3.3.1`.

AutoCrack preserves the full upstream Python package, CLI, compiled tracing agent, filters, prepend/append scripts, JSON output, spawn/attach modes, and programmatic APIs. The untouched upstream compiled agent is packaged at `upstream-original/jnitrace/build/jnitrace.js`.

## Frida 17 compatibility

Frida 17 removed the legacy static `Module.findExportByName()` API. Upstream jnitrace 3.3.1's compiled agent contains exactly three legacy global-export lookups:

- `Module.findExportByName(null, "dlopen")`
- `Module.findExportByName(null, "dlsym")`
- `Module.findExportByName(null, "dlclose")`

AutoCrack changes only those three calls to the Frida 17 equivalent `Module.findGlobalExportByName(...)`. The build fails if the expected upstream occurrences change or if any legacy `Module.findExportByName(` call remains.

This is a runtime-compatibility migration only. It does not alter JNI interception logic, library matching, JNI/JVM method filters, backtrace modes, exported-symbol filters, trace formatting, or target selection.

## Non-interactive execution

The upstream CLI intentionally waits on `input()` and handles `KeyboardInterrupt`. AutoCrack does not patch that behavior. Automated Agent validation keeps stdin open and terminates the CLI with SIGINT so the normal upstream shutdown path is exercised.
