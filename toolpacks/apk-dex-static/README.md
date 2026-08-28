# apk-dex-static

Trusted rootfs-only APK/DEX static-analysis toolpack for AutoCrackApp.

This toolpack packages pinned third-party analysis tools for Debian ARM64 rootfs execution:

- JADX CLI for bounded class decompilation
- Apktool for Android resource and smali decoding

The repository stores wrappers and packaging logic, not vendored upstream binaries.
