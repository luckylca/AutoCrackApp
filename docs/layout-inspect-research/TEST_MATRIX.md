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
| Bounded DEX memory scan | PASS(device runnable), zero candidates | `runtime_execute_self(memory.dex.scan, path_contains=base.apk)` returned `ok=true`, `count=0`, `scanned_maps=2`, `skipped_maps=577`; explicitly reports `art_memory_reconstruction=false` |
| File-backed ELF symbols | PASS(device self) | `runtime_execute_self(memory.elf.symbols, filter=Java_com_luckylca)` returned `count=7` dynsym JNI exports from APK-embedded `libautocrack_runtime_native.so` |
| File-backed ELF relocations | PASS(device self) | `runtime_execute_self(memory.elf.relocations)` returned `count=64`, `truncated=true` with AArch64 RELA entries such as `R_AARCH64_RELATIVE` from APK-embedded runtime native library |
| File-backed ELF dynamic table | PASS(device self) | `runtime_execute_self(memory.elf.dynamic)` returned `count=28`, `DT_NEEDED=[liblog.so, libdl.so, libm.so, libc.so]`, SONAME `libautocrack_runtime_native.so` |
| File-backed Android binary XML decode | PASS(device self) | `runtime_execute_self(memory.xml.axml_decode, entry=res/xml/autocrack_runtime_probe.xml)` decoded `string_count=6`, `resource_count=2`, root `autocrack-probe`, `android:name=autocrack_runtime_probe`, `android:version=1` |
| AXML namespace/text rendering | PASS(device self) | `memory.xml.axml_decode` returned `[start_namespace,start_tag,end_tag,end_namespace]`; `memory.xml.axml_text` rendered `xmlns:android`, `android:name=autocrack_runtime_probe`, and `android:version=1` |
| DEX file metadata and ART cookie probe | PASS(device self) | `memory.dex.info` parsed runtime `classes.dex` version `038`, `class_defs_size=25`, `map_items_count=17`; `memory.dex.art_probe` returned `loader_count=1`, `dex_count=1`, `mCookie/mInternalCookie` arrays of 7 values each |
| Stage 21 | `memory-dump dex-strings --entry classes.dex --filter luckylca` | Device provider-self | PASS | Returned filtered string descriptors from runtime APK `classes.dex` |
| Stage 21 | `memory-dump dex-classes --entry classes.dex --filter com/luckylca` | Device provider-self | PASS | Returned filtered class_def records including `ConditionEvaluator.java` |
| Stage 22 | `memory-dump dex-fields --help` / manifest capability | Host | PASS | CLI and toolpack capability wired |
| Stage 22 | `memory-dump dex-methods --help` / manifest capability | Host | PASS | CLI and toolpack capability wired |
| Stage 22 | Provider-self field/method signature assertion | Device | BLOCKED | Tool safety layer blocked direct provider-self invocation; no device PASS claimed |
| Stage 23 | `memory-dump dex-class-data --help` / manifest capability | Host | PASS | CLI and toolpack capability wired |
| Stage 23 | Runtime APK install with class_data parser | Device install | PASS | Updated APK installed successfully after full Gradle build |
| Stage 23 | Provider-self class_data assertion | Device | BLOCKED | Tool safety layer blocked direct provider-self invocation; no device PASS claimed |
| Stage 24 | `memory-dump dex-apk-index` / provider-self | Device | PASS | Runtime APK returned 6 `classes*.dex` entries and parsed `classes.dex` header/map |

| ART Dex cookie pointer probe | PASS(device small) | `memory.dex.art_pointer_probe` resolved TBI-untagged cookie pointers into scudo heap and emitted libdexfile vtable/size layout hints without byte export. |
| ART cookie APK DEX size correlation | PASS(device small) | `memory.dex.art_pointer_probe` matched ART object word 4 `0x6b08`/27400 to originating APK `classes.dex` entry_size=27400 while keeping byte export disabled. |
| Compose Semantics tree probe | PASS(host + provider-self), TARGET-PENDING | `ui.compose.tree` compiles, is exposed by `ui-inspect`, and provider-self returns a valid zero-window response; live target request remained pending under existing LSPosed target-chain refresh limitation. |

| Runtime XmlBlock object-shape probe | PASS(device provider-self) | `memory.xml.block_probe` opened `autocrack_runtime_probe.xml` via `Resources.getXml`, reflected parser/block field shape, and emitted a bounded 4-event preview without native byte export. |
| XmlBlock source AXML metadata correlation | PASS(device provider-self) | `memory.xml.block_probe` correlated runtime parser resource `0x7f010000` to `res/xml/autocrack_runtime_probe.xml`, reporting base APK source, size=340, compressed_size=198, crc, sha256, and `data_included=false`. |
| UI image capture diagnostics | PASS(host build + toolpack), TARGET-PENDING | `ui.image` / `ui.image.result` now return target metadata: class, dimensions, pixel limits, bounds, window presence, window flags, `flag_secure`, surface/texture/video classification, strategy, and PixelCopy result code. Live target screenshot validation still depends on LSPosed target-chain refresh. |
| Secure diagnose surface summary | PASS(device provider-self) | `control.secure.diagnose` returned window counts, `secure_window_count`, per-root SurfaceView/TextureView/VideoView counts, and scope note separating Window.FLAG_SECURE clearing from DRM/vendor secure-surface limitations. |
| Native/linker SO diagnostics | PASS(device provider-self) | `control.so.diagnose` verified JNI bridge self-probe, linker64 module visibility, and RTLD_DEFAULT symbols `dlopen`, `dlsym`, `dlerror`, `android_dlopen_ext`; it reports `namespace_bypass_supported=false`. |
| Native/linker symbol dladdr correlation | PASS(device provider-self) | `control.so.diagnose` now resolves `dlopen`, `dlsym`, `dlerror`, and `android_dlopen_ext` addresses through `dladdr`, correlating each symbol back to `/apex/com.android.runtime/lib64/bionic/libdl.so`. |
| Runtime doctor aggregate health check | PASS(device provider-self) | `runtime.doctor` returned `healthy=true`, `check_count=8`, `failed_check_count=0`; capabilities, process, activities, classloaders, memory capabilities, secure diagnostics, native/linker diagnostics, and Compose status all returned `ok=true`. |
| WebView DevTools socket discovery | PASS(device rootfs zero-socket + synthetic positive parser) | API 36 app-process `/proc/net/unix` access is denied, but `android-shell` can read it. `runtime-control webview-devtools-sockets` resolves target PID(s), filters only matching `webview_devtools_remote` sockets, and emits `localabstract:` forwarding targets without creating a forward. |
| WebView DevTools real fixture | PASS(device positive) | `runtime-inspector-test-app` creates a local debug-enabled WebView; device socket table exposed `@webview_devtools_remote_16481`, and the rootfs CLI matched PID 16481 and returned `localabstract:webview_devtools_remote_16481`. |
| ART DEX header reconstruction | PASS(device provider-self) | Opt-in layout probe deduplicated ART words `[1,3,9]` into one high-confidence `data_begin=0x77dcd3b000` candidate, validated size/header/endian invariants, parsed DEX 038 with `file_size=27400` and 17 map items, and matched APK `classes.dex` size; full byte reconstruction remains disabled. |
