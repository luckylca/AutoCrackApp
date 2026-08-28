# Device verification flow

This checklist verifies the Android/rootfs-only runtime without requiring a desktop worker at final runtime.

## Build inputs

1. Build the debug APK with the project wrapper:

```bash
./gradlew --no-daemon --max-workers=1 --stacktrace lintDebug testDebugUnitTest assembleDebug
```

2. Build or download the Debian rootfs package from `.github/workflows/rootfs-package.yml`:

```text
AutoCrackApp-debian-bookworm-arm64-rootfs.zip
```

The rootfs package must contain `manifest.json` and `rootfs.tar.xz`.

3. Prepare the transfer bundle:

```bash
scripts/prepare_device_verification_bundle.sh --rootfs-zip /path/to/AutoCrackApp-debian-bookworm-arm64-rootfs.zip
```

If `--rootfs-zip` is omitted, the bundle is still created with `rootfs/ROOTFS_PENDING.txt`.

## On-device order

1. Install `apk/AutoCrackApp-debug.apk`.
2. Open **Debian Root Runtime** and import the rootfs zip.
3. Run the rootfs health command and confirm `python3`, `readelf`, and Java are present.
4. Open **Toolpacks** and import the toolpack zips from `toolpacks/`.
5. Run each toolpack self-test:
   - `rootfs-pcap-analysis`
   - `elf-native-static`
   - `apk-dex-static`
   - `rizin-deep-static`
   - `perfetto-analysis`
   - `android-frida`
   - `android-lldb-server`
6. Run device diagnostics and copy the report.
7. For a static APK smoke test, run JADX/Apktool and native risk/string summaries on a known test APK.
8. For a dynamic network smoke test, run process identity, connection snapshot, bounded pcap capture, pcap summary, and Frida network-stack detection against a target app.

## Expected boundaries

- No desktop runtime worker.
- No ADB dependency after installation/import.
- No VPNService.
- No Surfing/box config mutation.
- No CA installation.
- No default MITM.
- pcap analysis is metadata-only unless a later explicitly authorized MITM tool is added.
