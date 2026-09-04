# Android Frida skill

Use this Toolpack for live Android instrumentation. The Frida client runs in Debian; `android-frida-server` manages the pinned ARM64 server on the Android host through `android-shell`.

## First steps

```bash
android-frida-server start
frida-ps -H 127.0.0.1:27042
frida --help
```

## Typical workflow

1. Start the managed server and verify `status`.
2. Discover the process with `frida-ps` or `android-shell pidof PACKAGE`.
3. Prefer a small, task-specific `-e` expression or script under `/workspace`.
4. Save useful output/files under `/workspace`.
5. Stop the managed server when the task no longer needs it: `android-frida-server stop`.

`frida-autocrack-client --help` exposes the reviewed AutoCrack helper workflows. The upstream `frida-*` commands remain available for advanced work.

The lifecycle helper only recovers the verified legacy AutoCrack helper at `/data/local/tmp/frida-server-android`; it does not kill arbitrary processes that merely use port 27042. Frida scripts can modify a target, so keep inspection scripts distinct from mutation scripts.
