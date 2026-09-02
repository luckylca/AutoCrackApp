# AutoCrackApp

AutoCrackApp is an Android-first APK reverse-analysis agent for authorized security research and software interoperability work.

## Final runtime target

The production Agent runtime target is `android_rootfs_only`: every Agent-callable tool must run inside the installed Android app, Android root shell, Android loopback, or the Debian ARM64 rootfs. Mac, Windows, desktop Linux daemons, ADB, and developer workstation paths are allowed for development and GitHub Actions toolpack builds only; they are not allowed as final Agent runtime dependencies.

See [Final runtime target](docs/FINAL_RUNTIME_TARGET.md) and [Android/rootfs network capture plan](docs/ANDROID_ROOTFS_NETWORK_CAPTURE.md).

## Current architecture

Mobile Pi Agent uses four primitive actions (`exec_bash`, `read_file`, `write_file`, and `kill_process`) in one Debian ARM64 environment. Toolpacks remain independently verified and atomically installed, while active toolpack commands share `/usr/local/bin` and Python, Node, and Java dependencies share their conventional runtime search paths. Mature upstream CLIs and language APIs are the capability surface; AutoCrack helpers are optional conveniences.

Android-host commands run through `android-shell`, which forwards root argv without a command allowlist. Package/data changes, mount control, system-path writes, destructive recursive deletion of critical trees, block-device writes/formatting, and reboot/poweroff/halt are classified before execution. The on-device policy can deny system writes or require an explicit confirmation. Existing typed bridges remain available for legacy UI diagnostics and recovery, but the Mobile Pi Agent does not depend on them.

## Earlier milestone

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

Local validation should use the same Gradle line as CI. Gradle 9.6.x is not a supported local runner for this AGP 8.x project; use Gradle 8.13 or the CI workflow when checking Android unit tests and APK assembly.

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

External model use is optional and requires an explicit button press. The earlier Phase 5 evidence-analysis flow sends only:

- the user's question;
- compact Manifest and archive statistics;
- DEX index counts;
- at most 60 ranked evidence records.

That earlier flow does not send APK, DEX, SO, signing-certificate bytes, application private data, or the complete DEX index.

Mobile Pi Agent has a broader boundary: the configured external model receives the conversation, attachment descriptions and text made available to the Agent, and bounded tool results needed for the tool loop. Binary attachments remain in the private on-device workspace unless a tool or user instruction explicitly converts or uploads them, but extracted text and command output can be included in model requests. Review the selected provider's data policy before using secrets or third-party application data.

Provider URLs must use HTTPS. API configuration is encrypted using an Android Keystore key, but a fully compromised Root device cannot provide absolute secret protection.

Model settings support multiple named providers with one active provider at a time. Each provider explicitly selects either OpenAI Chat Completions-compatible or Anthropic Messages-compatible request formatting. The app can discover models through the provider's `GET /v1/models` endpoint, test connectivity and authentication without an inference request, or send a separate minimal `hi` request to the selected model. Providers that do not expose a model-list endpoint can still use a manually entered model id.

## Safety

Use AutoCrackApp only on applications and devices you own or are explicitly authorized to analyze. Mobile Pi Agent intentionally has general Debian Bash and Android host root-command access. Disabling dangerous-operation confirmation is equivalent to granting the Agent full Root execution without per-command prompts; confirmation and command classification are not substitutes for authorization or careful review. Toolpack hashes, timeouts, bounded audit logs, process identity checks, loopback validation, and cleanup remain integrity and reliability controls.

See [Phase 5 documentation](docs/PHASE_5.md) for the index schema, privacy boundary, and device test checklist.
