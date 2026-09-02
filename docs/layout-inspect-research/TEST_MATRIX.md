# Test Matrix

## Baseline captured before this consolidation pass

- Branch: `codex/frida-capabilities-1.0.4`.
- Consolidation start HEAD: `e46c3e2e678edd380c62c3c23f21faea6ddf4238`.
- The current pass inherited uncommitted shared Runtime / Toolpack migration work and preserves it instead of reverting it.
- Current Runner device access: `adb` and `android-shell` were not available, so real-device validation remains blocked in this session.

## Required release gates

| Layer | Required proof |
|---|---|
| Host | manifest v1/v2 parse, trust/dependency checks, maps parser, JSON envelope, object serializer budgets |
| Build | all runtime/test APK assemblies, Toolpack packages, manifest validation; lint/JVM tests when dependencies are available |
| Install | install/uninstall/reinstall each Toolpack without breaking other commands |
| Shared runtime | one installed module package and one injected entry; all capability groups respond |
| UI | Activity, Dialog, PopupWindow, multiple roots, listener details, transform-aware pick, mutation, image |
| View/Object | `ui-inspect at` handle succeeds in `runtime-inspect object` |
| Listener/Hook | listener handle -> class signature -> SimpleHook rule -> click -> log |
| Loader/Dex | loader handle -> selected Dex dump -> valid Dex header -> JADX opens output |
| Activities | running instance and Intent; declared list; start Activity |
| Memory | parsed maps, selected mapping, module segments, SO artifact/hash |
| WebView | info, enable debug, evaluate JS result |
| Secure | enumerate secure window, clear flag, verify status |
| SimpleHook | complete existing device matrix after shared-runtime migration |

Failures caused by an unsupported API-specific strategy are recorded as
`UNSUPPORTED`, not PASS. A release report lists PASS/FAIL/UNSUPPORTED per row.

## Current host validation evidence

- Gradle build uses isolated `GRADLE_USER_HOME=.gradle-autocrack-runtime` because the user-level `~/.gradle/init.gradle` injects repositories that conflict with this repository's settings repository policy.
- `:autocrack-runtime:assembleDebug`, `:app:assembleDebug`, `:runtime-inspector-test-app:assembleDebug`, and `:simplehook-test-app:assembleDebug` passed offline.
- Python `compileall` passed for the shared client and all CLI entrypoints.
- CLI `--help` smoke passed for `simplehook`, legacy `runtime-inspector`, `ui-inspect`, `runtime-inspect`, `memory-dump`, and `runtime-control`.
- Toolpack packaging passed for the two legacy Toolpacks and the four new schema-v2 Toolpacks.
- Zip/manifest validation passed: each package contains `manifest.json` and `payload.zip`, payload SHA/size match, and schema-v2 packages contain `requires`.
- Python `pytest` was not available in the current Python 3.14/3.13 environments, so pytest-based tests were not executed.
- Real-device install/runtime/cross-tool validation was not executed because no Android bridge command was available in the current Runner.


## Stage 2 host validation

| Layer | Status | Evidence |
|---|---|---|
| UI relationships/find | PASS(host build) | `:autocrack-runtime:assembleDebug` after adding `ui.find`, `ui.parent`, `ui.children`, `ui.siblings` |
| Runtime object control | PASS(host build) | `:autocrack-runtime:assembleDebug` after adding `control.object.field.set` and `control.object.method.call` |
| CLI discovery | PASS(host) | `ui-inspect find --help`, `runtime-control object-field-set --help`, `runtime-control object-method-call --help` |
| Activity lifecycle metadata | PASS(host build) | `:autocrack-runtime:assembleDebug` after adding ActivityRegistry lifecycle timestamps and event order |
| APK entry enumeration/pull | PASS(host build) | `:autocrack-runtime:assembleDebug`, `memory-dump apk-entries --help`, `memory-dump apk-pull --help`, manifest capability check |
| WebView navigation/control | PASS(host build) | `:autocrack-runtime:assembleDebug`, runtime-control WebView help checks, manifest capability check |
| Module file-backed dump | PASS(host build) | `:autocrack-runtime:assembleDebug`, `memory-dump module-file-dump --help`, manifest capability check |
| Device behavior | BLOCKED | Android host bridge is unavailable in the current WebCodex/Mac session; must run device matrix once bridge variables are present. |
