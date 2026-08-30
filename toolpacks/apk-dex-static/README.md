# apk-dex-static

Trusted rootfs-only APK/DEX static-analysis toolpack for AutoCrackApp.

This toolpack packages pinned third-party analysis tools for Debian ARM64 rootfs execution:

- Standard JADX CLI
- Apktool for Android resource and smali decoding

Mobile resource policy:

- `jadx` defaults to a 768 MB JVM heap instead of upstream's `MaxRAMPercentage=70%`.
- JADX defaults to 2 worker threads to reduce CPU pressure and concurrent allocation.
- Override when needed with any positive integer `AUTOC_JADX_HEAP_MB` and `AUTOC_JADX_THREADS` values that fit the physical device.
- `jadx --autocrack-policy` prints the effective defaults.
- For large APKs, prefer `--single-class` / narrowed analysis before a full decompile.

The repository stores wrappers and packaging logic, not vendored upstream binaries.

Both wrappers pass all upstream arguments through unchanged. The defaults are a resource policy for phones, not an analysis capability boundary.
