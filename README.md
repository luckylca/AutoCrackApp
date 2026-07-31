# AutoCrackApp

AutoCrackApp is an Android-first APK reverse-analysis agent for authorized security research and software interoperability work.

## Current milestone

Phase 2 provides:

- A native Kotlin and Jetpack Compose application.
- Root availability and KernelSU authorization detection.
- Current-user package discovery through a fixed, type-safe Root tool.
- Searchable installed-package results with UID and primary APK path.
- `pm path` parsing for `base.apk` and Split APKs.
- Root-assisted copying into an app-private workspace.
- File-size and SHA-256 verification for every extracted APK.
- Unit tests for Root output, package parsing, path parsing, and Shell argument quoting.
- GitHub Actions lint, tests, debug build, checksum generation, and APK artifact upload.

Phase 2 does not decompile DEX, decode resources, execute target code, analyze SO instructions, instrument processes, or call an external LLM.

## Build

The CI workflow installs Gradle 8.13 and runs:

```bash
gradle --no-daemon --stacktrace clean lintDebug testDebugUnitTest assembleDebug
```

The resulting artifact contains:

```text
AutoCrackApp-phase2-debug.apk
AutoCrackApp-phase2-debug.apk.sha256
```

## Supported baseline

- Android 8.0 or later (`minSdk 26`).
- Android API 36 compile and target SDK.
- Rooted test devices, with KernelSU as the first supported root manager.

## Workspace

Extracted APK copies are stored under AutoCrackApp's private directory:

```text
files/workspaces/<package-name>/session-<timestamp>/
```

Every extraction session records the Base APK, all returned Split APKs, file sizes, and SHA-256 digests. A failed session is removed instead of leaving an incomplete workspace.

## Safety

Use AutoCrackApp only on applications and devices you own or are explicitly authorized to analyze. The app does not accept arbitrary Root Shell commands. Package names, Android user IDs, source paths, destinations, ownership, permissions, and timeouts are constructed by typed tools defined in application code.

See [Phase 2 documentation](docs/PHASE_2.md) for the security boundary and device test checklist.
