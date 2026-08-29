# Android Host Shell

Use `android-shell` when a task needs the real Android host rather than the Debian rootfs.

The command runs through AutoCrackApp's existing root runtime and therefore executes as Android root while preserving timeout, audit, cancellation, and dangerous-operation approval.

Common commands:

```bash
android-shell id
android-shell getprop ro.product.model
android-shell pm list packages
android-shell pm path com.ss.android.ugc.aweme
android-shell am start -n package/activity
android-shell dumpsys package com.ss.android.ugc.aweme
android-shell cmd package list packages
android-shell settings get global airplane_mode_on
android-shell logcat -d -t 200
```

For pipelines or Android shell syntax, explicitly invoke the host shell:

```bash
android-shell sh -c 'pm list packages | grep aweme'
```

The Android host command starts in the host-visible directory backing Debian `/workspace`. The client also automatically maps literal `/workspace` paths in Android-host arguments and `sh -c` scripts to that backing directory. Therefore a host file can be imported directly into the Agent workspace:

```bash
android-shell sh -c 'cp "$(pm path com.ss.android.ugc.aweme | sed -n "s/^package://p" | head -1)" /workspace/douyin-base.apk'
file /workspace/douyin-base.apk
```

Do not use Debian `pm`, `am`, `cmd`, `dumpsys`, `getprop`, or `/system/bin/*` directly. Use `android-shell` for Android-native commands.
