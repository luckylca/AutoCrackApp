# AutoCrackApp

AutoCrackApp is an Android-first APK reverse-analysis agent for authorized security research and software interoperability work.

## Current milestone

Phase 3 provides:

- A native Kotlin and Jetpack Compose application.
- Root availability and KernelSU authorization detection.
- Current-user package discovery through fixed, type-safe Root tools.
- Searchable installed-package results with UID and primary APK path.
- `pm path` parsing and verified extraction of Base and Split APKs.
- Local Manifest metadata parsing through Android `PackageManager`.
- Current and historical signing-certificate SHA-256 fingerprints.
- Requested permissions, declared permissions, component counts, and exported-component summaries.
- ZIP inventory for resources, assets, `META-INF`, nested APKs, and suspicious paths.
- DEX file discovery with header-version and magic validation.
- Native-library discovery with ABI, ELF class, and machine architecture parsing.
- A structured `analysis-report.json` stored beside each extracted APK set.
- GitHub Actions lint, JVM tests, debug build, checksum generation, and APK artifact upload.

Phase 3 does not decompile DEX instructions, reconstruct Java/Kotlin source, decode binary XML into source form, execute target DEX/SO code, instrument processes, or call an external LLM.

## Build

The CI workflow installs Gradle 8.13 and runs:

```bash
gradle --no-daemon --stacktrace clean lintDebug testDebugUnitTest assembleDebug
```

The resulting artifact contains:

```text
AutoCrackApp-phase3-debug.apk
AutoCrackApp-phase3-debug.apk.sha256
```

## Supported baseline

- Android 8.0 or later (`minSdk 26`).
- Android API 36 compile and target SDK.
- Rooted test devices, with KernelSU as the first supported root manager.

## Workspace

Each analysis session is stored under AutoCrackApp's private directory:

```text
files/workspaces/<package-name>/session-<timestamp>/
```

A successful session contains the Base APK, all Split APKs returned by Android, and:

```text
analysis-report.json
```

Every APK is checked for non-zero size and SHA-256 before analysis. The static analyzer only reads the private copies and never loads target code into the AutoCrackApp process.

## Safety

Use AutoCrackApp only on applications and devices you own or are explicitly authorized to analyze. The app does not accept arbitrary Root Shell commands. Package names, Android user IDs, source paths, destinations, ownership, permissions, and timeouts are constructed by typed tools defined in application code.

See [Phase 3 documentation](docs/PHASE_3.md) for the report schema, security boundary, and device test checklist.
