# Final runtime target: Android + rootfs only

AutoCrackApp's production Agent runtime target is:

```text
FINAL_RUNTIME_TARGET = android_rootfs_only
```

This means the final app may use GitHub Actions or another cloud CI system to **build** signed and pinned toolpack artifacts, but every tool that the installed app exposes to the Agent must run on the Android device itself.

## Allowed final-runtime execution surfaces

Agent-callable tools may execute only on these surfaces:

```text
Android app process
Android app private workspace
Android root shell through the user's installed su provider
Android host OS interfaces such as /proc, logcat, settings, iptables/nftables, and dumpsys
Debian ARM64 rootfs through AutoCrackApp's ChrootRuntimeEngine
Trusted ARM64 / Android toolpacks installed into the rootfs or app-managed runtime directories
Android loopback 127.0.0.1 between device-local helpers and device-local clients
```

The word `host` in older class and catalog names means **Android host OS**, not a Mac, Windows, or Linux desktop host. New code should prefer `androidHost`, `device`, or `rootfs` terminology instead of adding more generic `host` names.

## Allowed build-time dependencies

The following are allowed outside the device because they are build, packaging, or developer-test infrastructure, not final Agent runtime dependencies:

```text
GitHub Actions toolpack builds
Gradle / Android SDK / NDK during development
ADB during development and debug validation
Local artifact download and installation during development
```

A GitHub Actions workflow may download upstream source releases, verify SHA-256 values, assemble deterministic toolpack ZIP files, and publish artifacts. The installed app must only trust the resulting pinned manifests and payload hashes.

## Forbidden final-runtime dependencies

Agent-callable production tools must not depend on:

```text
Mac, Windows, or desktop Linux host scripts
ADB or platform-tools
Desktop-side daemons, HTTP servers, proxy workers, Ghidra workers, mitmproxy instances, or tshark processes
Absolute developer paths such as /Users/<name>/...
Desktop architecture binaries such as darwin-* or linux-x64 when used as runtime executables
Network callbacks to a developer workstation
```

If a capability needs a large binary, it must be packaged as an Android ARM64 or rootfs ARM64 toolpack and installed through the trusted toolpack path.

## Runtime naming rules for new work

New Agent-facing tools should use names that make the final execution surface explicit:

```text
android.process.*       # Android /proc, UID, maps, fd, socket, logcat evidence
android.net.*           # Android root networking and Surfing/box integration
rootfs.apk.*            # JADX / Apktool inside Debian ARM64 rootfs
rootfs.native.*         # Rizin, LIEF, readelf, checksec inside rootfs
rootfs.pcap.*           # tshark / pcap analysis inside rootfs
android.frida.*         # Android frida-server plus rootfs client over 127.0.0.1
android.debugger.*      # Android lldb-server plus loopback control
```

Existing `host.*` IDs may remain as compatibility aliases until the UI and tests are migrated, but new IDs should not use ambiguous desktop-host terminology.

## Review checklist

Before adding a new Agent tool, verify:

1. It runs on Android or Debian ARM64 rootfs without a desktop worker.
2. It uses only app-managed workspace paths or Android system paths intentionally accessed through root.
3. It does not invoke ADB, platform-tools, `/Users/...`, or host-local services.
4. It exposes typed parameters, not arbitrary shell, arbitrary SQL, arbitrary JavaScript, arbitrary proxy config, or arbitrary file paths.
5. Long-lived helpers are bound to the selected package/UID when possible and have a deterministic stop/recovery path.
6. Any device-wide state change, especially networking, certificates, iptables/nftables, proxy settings, or debugger attach, is audited and reversible.
