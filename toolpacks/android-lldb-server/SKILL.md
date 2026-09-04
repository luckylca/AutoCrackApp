# Android LLDB skill

Use `lldb` as the Debian client and `android-lldb-server` when the LLDB server must execute on the Android host.

## First steps

```bash
lldb --version
android-lldb-server --help
android-shell pidof PACKAGE
```

## Typical workflow

1. Resolve and verify the target PID on Android.
2. Start the host-side server with `android-lldb-server` using the mode shown by `--help`.
3. Connect the Debian `lldb` client to the returned/listening loopback endpoint.
4. Inspect modules, threads, registers, disassembly, and memory with bounded commands.
5. Detach/quit and stop the server when finished.

The server binary lives in the Toolpack's host-visible path while the LLDB client and Python runtime live inside Debian. Read-only inspection is preferred by default; `memory write` and `register write` explicitly mutate target state.
