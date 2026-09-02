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


| Native bridge self probe | PASS(device self) | `runtime_execute_self(memory.capabilities)` and `runtime_execute_self(memory.native.probe)` on `a4976c80`; verified JNI load, `process_vm_readv` marker read, and `dladdr` symbol/file resolution |
| Runtime request provider-pull channel | PASS(host build), BLOCKED(target refresh) | `runtime_pending` provider method and InspectorChannel provider-first polling compile; target process retest is blocked until LSPosed refreshes `com.luckylca.autocrack.runtime` to the latest installed APK path |
| Filtered maps return | PASS(host build) | `memory.maps` supports `path_contains` and `permissions_contains` filters to avoid Binder transaction overflow on large `/proc/self/maps` responses |

| Binary APK XML extraction | PASS(device self) | `runtime_execute_self(memory.xml.binary)` extracted `res/xml/autocrack_runtime_probe.xml`; returned base64 raw APK AXML with `binary_axml=true`, `memory_reconstruction=false`, size 340, SHA-256 `62c63b30f0e773b1511f223cbe6ff5c7e11e7fc983afa33839147530059c1400` |

| Native ELF module enumeration | PASS(device self) | `runtime_execute_self(memory.native.modules, filter=autocrack)` used JNI `dl_iterate_phdr`; returned the runtime native library from `base.apk!/lib/arm64-v8a/libautocrack_runtime_native.so` with base/load ranges, `phdr_count=9`, `load_segments=3` |

| ART Dex cookie probe | PASS(host/install), DEVICE ASSERTION BLOCKED | `memory.dex.art_probe` compiled, Toolpack rebuilt, CLI help passed, runtime APK installed. Short provider-self assertion was blocked by tool safety checks, so target/device result is not claimed. |

| Native symbol resolution | PASS(device self) | `runtime_execute_self(control.so.dlsym, symbol=dlopen)` resolved `RTLD_DEFAULT` `dlopen` to `0x77d932c01c`; result marks `callable=false` and does not invoke the symbol |

| Android dlopen ext | PASS(host), DEVICE ASSERTION BLOCKED | `control.so.android_dlopen_ext` compiled, `runtime-control` rebuilt, CLI/manifest checks passed. Direct provider-self load assertion was blocked by tool safety checks, so device result is not claimed. |

| ELF metadata parsing | PASS(device self) | `runtime_execute_self(memory.elf.info)` parsed `lib/arm64-v8a/libautocrack_runtime_native.so` from the installed runtime APK; result was `ELF64`, `EM_AARCH64`, `phnum=9`, `load_segments=3`, Build-ID `89d021ea6f492c3cbca67001f0d1f97d4541e0a9`, `bytes_read=412664`, `truncated=false` |

| Host output materialization | PASS(host) | `memory-dump --output` help checks passed for read/module/dex/assets/XML/APK pull commands; local unit checks wrote single bytes, XML text, and module segment manifests |
| File-backed ELF info | PASS(device self) | `runtime_execute_self(memory.elf.info)` parsed APK-embedded `lib/arm64-v8a/libautocrack_runtime_native.so`; returned ELF64/AARCH64, `phnum=9`, `program_headers=9`, `load_segments=3`, GNU build-id `89d021ea6f492c3cbca67001f0d1f97d4541e0a9` |
