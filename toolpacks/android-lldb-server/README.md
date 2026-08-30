# Standard LLDB client and Android server toolpack

Packages the pinned patched Android ARM64 `lldb-server` together with the standard Debian ARM64 LLDB 14 client and its private runtime dependencies.

`lldb` is the upstream command-line client. It supports the normal LLDB command surface, including `gdb-remote`, `process connect`, attach, breakpoints, memory/register reads and writes, and thread stepping. `android-lldb-server` forwards all argv unchanged to the bundled server in the Android host namespace; run it as a background job when connecting from `lldb`.

AutoCrack's typed debugger bridge remains available for UI diagnostics and recovery. It is not the Mobile Pi Agent's debugger capability boundary.
