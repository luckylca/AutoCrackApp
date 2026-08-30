# AutoCrack Frida toolpack

This toolpack packages the official Frida Android ARM64 server, ARM64 Python bindings, and the upstream `frida-tools` console suite in one installable versioned unit.

Once installed, the Mobile Pi Agent gets the standard Frida experience: `frida`, `frida-ps`, `frida-trace`, `frida-kill`, device/file/process utilities, arbitrary user-authored Frida JavaScript loaded through the normal CLI, and ordinary Python `import frida` from the shared Debian toolpack environment. Toolpack isolation is only for installation, upgrade, uninstall, provenance, and version verification; it is not a runtime capability sandbox.

`frida-autocrack-client` and the compiled AutoCrack RPC agent are retained as optional convenience/compatibility helpers for common inspection tasks. They are not the Agent's Frida capability boundary and do not replace the upstream CLI.

The Android binary is managed explicitly from a Mobile Agent session:

```text
android-frida-server start
android-frida-server status
android-frida-server stop
```

Each command returns one JSON object containing `host`, `port`, `pid`, and `serverVersion`. The standard endpoint is `127.0.0.1:27042`, so upstream clients can connect with `frida-ps -H 127.0.0.1:27042`. The lifecycle helper does not filter or wrap the Frida API.

The final toolpack ZIP is produced only by `.github/workflows/frida-toolpack.yml`.
