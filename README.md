# AutoCrackApp

AutoCrackApp is an Android-first APK reverse-analysis agent for authorized security research and software interoperability work.

## Current milestone

Phase 5 provides an end-to-end static-analysis Agent MVP:

- Native Kotlin and Jetpack Compose UI.
- KernelSU-first Root authorization and typed Root operations.
- Installed-app discovery and verified Base/Split APK extraction.
- Manifest, signing certificate, permission, component, ZIP, DEX, and native-library inventory.
- Detailed non-ELF diagnostics with path, size, header bytes, and reason.
- Persistent local DEX evidence indexing for defined classes, methods, fields, and string references.
- Natural-language question expansion and ranked local evidence search.
- Optional OpenAI-compatible model requests that send only bounded summaries and selected evidence.
- Android Keystore encrypted storage for the external provider configuration.
- Copyable on-device test reports for direct feedback.
- GitHub Actions lint, JVM tests, debug build, checksum generation, and APK artifact upload.

Phase 5 does not reconstruct full Java/Kotlin source, execute target DEX/SO code, inject into processes, bypass application protections, upload APK files, or prove that a matching method executes at runtime.

## Build

The CI workflow installs Gradle 8.13 and runs:

```bash
gradle --no-daemon --stacktrace clean lintDebug testDebugUnitTest assembleDebug
```

The resulting artifact contains:

```text
AutoCrackApp-phase5-debug.apk
AutoCrackApp-phase5-debug.apk.sha256
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

A successful Phase 5 session includes:

```text
analysis-report.json
dex-index.db
dex-index-summary.json
agent-query-<timestamp>.json
```

Every APK is checked for non-zero size and SHA-256 before analysis. The analyzer only reads private copies and never loads target code into the AutoCrackApp process.

## External model boundary

External model use is optional and requires an explicit button press. AutoCrackApp sends only:

- the user's question;
- compact Manifest and archive statistics;
- DEX index counts;
- at most 60 ranked evidence records.

It does not send APK, DEX, SO, signing-certificate bytes, application private data, or the complete DEX index. Provider URLs must use HTTPS. API configuration is encrypted using an Android Keystore key, but a fully compromised Root device cannot provide absolute secret protection.

## Safety

Use AutoCrackApp only on applications and devices you own or are explicitly authorized to analyze. The app does not accept arbitrary Root Shell commands. Package names, Android user IDs, source paths, destinations, ownership, permissions, and timeouts are constructed by typed tools defined in application code.

See [Phase 5 documentation](docs/PHASE_5.md) for the index schema, privacy boundary, and device test checklist.
