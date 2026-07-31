# AutoCrackApp

AutoCrackApp is an Android-first APK reverse-analysis agent for authorized security research and software interoperability work.

## Current milestone

Phase 1 provides:

- A native Kotlin and Jetpack Compose application.
- Root availability and authorization detection.
- KernelSU identification using version and filesystem evidence.
- A read-only diagnostics screen.
- Unit tests for root-output parsing.
- GitHub Actions verification and a downloadable debug APK artifact.

No APK extraction, decompilation, native-code analysis, dynamic instrumentation, or external LLM calls are enabled yet.

## Build

The CI workflow installs Gradle 8.13 and runs:

```bash
gradle --no-daemon --stacktrace clean lintDebug testDebugUnitTest assembleDebug
```

The resulting artifact contains:

```text
AutoCrackApp-phase1-debug.apk
AutoCrackApp-phase1-debug.apk.sha256
```

## Supported baseline

- Android 8.0 or later (`minSdk 26`).
- Android API 36 compile and target SDK.
- Rooted test devices, with KernelSU as the first supported root manager.

## Safety

Use AutoCrackApp only on applications and devices you own or are explicitly authorized to analyze. The agent architecture will keep LLM planning separate from type-safe, audited device tools; models will not receive unrestricted root-shell access.

See [Phase 1 documentation](docs/PHASE_1.md) for the device test checklist and security boundary.
