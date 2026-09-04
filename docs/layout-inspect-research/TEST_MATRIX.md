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
- `:autocrack-runtime:assembleDebug`, `:app:assembleDebug`, and the unified `:runtime-inspector-test-app:assembleDebug` passed offline; the former `:simplehook-test-app` module was removed in Stage 58.
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
| Compose Semantics tree probe | PASS(host + provider-self + target no-view response) | `ui.compose.tree` compiles, is exposed by `ui-inspect`, provider-self returns a valid zero-window response, and the owned runtime-inspector target completed a live request with `ok=true`, `android_compose_view_count=0`, and `node_count=0` because the fixture app has no ComposeView. |

| Runtime XmlBlock object-shape probe | PASS(device provider-self) | `memory.xml.block_probe` opened `autocrack_runtime_probe.xml` via `Resources.getXml`, reflected parser/block field shape, and emitted a bounded 4-event preview without native byte export. |
| XmlBlock source AXML metadata correlation | PASS(device provider-self) | `memory.xml.block_probe` correlated runtime parser resource `0x7f010000` to `res/xml/autocrack_runtime_probe.xml`, reporting base APK source, size=340, compressed_size=198, crc, sha256, and `data_included=false`. |
| UI image capture diagnostics | PASS(host build + toolpack + target image) | `ui.image` / `ui.image.result` return target metadata: class, dimensions, pixel limits, bounds, window presence, window flags, `flag_secure`, surface/texture/video classification, strategy, and PixelCopy result code. Live target capture passed on the DecorView root with `ok=true`, strategy `View.draw(Canvas)`, PNG/base64 output, size `1224x2912`, and `flag_secure=false`. |
| Secure diagnose surface summary | PASS(device provider-self) | `control.secure.diagnose` returned window counts, `secure_window_count`, per-root SurfaceView/TextureView/VideoView counts, and scope note separating Window.FLAG_SECURE clearing from DRM/vendor secure-surface limitations. |
| Native/linker SO diagnostics | PASS(device provider-self) | `control.so.diagnose` verified JNI bridge self-probe, linker64 module visibility, and RTLD_DEFAULT symbols `dlopen`, `dlsym`, `dlerror`, `android_dlopen_ext`; it reports `namespace_bypass_supported=false`. |
| Native/linker symbol dladdr correlation | PASS(device provider-self) | `control.so.diagnose` now resolves `dlopen`, `dlsym`, `dlerror`, and `android_dlopen_ext` addresses through `dladdr`, correlating each symbol back to `/apex/com.android.runtime/lib64/bionic/libdl.so`. |
| Runtime doctor aggregate health check | PASS(device provider-self) | `runtime.doctor` returned `healthy=true`, `check_count=8`, `failed_check_count=0`; capabilities, process, activities, classloaders, memory capabilities, secure diagnostics, native/linker diagnostics, and Compose status all returned `ok=true`. |
| WebView DevTools socket discovery | PASS(device rootfs zero-socket + synthetic positive parser) | API 36 app-process `/proc/net/unix` access is denied, but `android-shell` can read it. `runtime-control webview-devtools-sockets` resolves target PID(s), filters only matching `webview_devtools_remote` sockets, and emits `localabstract:` forwarding targets without creating a forward. |
| WebView DevTools real fixture | PASS(device positive) | `runtime-inspector-test-app` creates a local debug-enabled WebView; device socket table exposed `@webview_devtools_remote_16481`, and the rootfs CLI matched PID 16481 and returned `localabstract:webview_devtools_remote_16481`. |
| ART DEX header reconstruction | PASS(device provider-self) | Opt-in layout probe deduplicated ART words `[1,3,9]` into one high-confidence `data_begin=0x77dcd3b000` candidate, validated size/header/endian invariants, parsed DEX 038 with `file_size=27400` and 17 map items, and matched APK `classes.dex` size; full byte reconstruction remains disabled. |
| ART DEX bounded table reconstruction | PASS(device provider-self) | `try_layout_dex_tables` parsed the validated ART-memory DEX directly: totals 455 strings / 25 classes / 63 fields / 146 methods / 25 class_data; bounded 4-item previews all returned `ok=true`, with `raw_bytes_included=false`. |
| ART DEX content fingerprint correlation | PASS(device via Stage 41 verify-only) | Validated ART candidates compute memory SHA-256/DEX signature and compare same-size originating APK `classes*.dex`; Stage 41 device verification returned `exact_apk_content_match=true` and the same memory/APK SHA-256 without transporting raw bytes. |
| ClassLoader context/activity discovery fallback | PASS(device provider-self) | `runtime.classloaders` returned PathClassLoader + BootClassLoader with `registry_count=0`; the primary loader carried context/application/runtime sources and runtime APK DexFile metadata. `runtime.process.classloader_count=2` also passed. |
| Default class-search over discovered loaders | PASS(host build), DEVICE ASSERTION BLOCKED | `runtime.class.search` now uses the merged discovered-loader set instead of registry-only loaders; direct provider-self search assertion was blocked by tool safety. |
| ART DEX bounded raw dump revalidation | PASS(device verify-only + host/CLI build) | `memory.dex.art_dump` selected one high-confidence ART candidate at `0x77dcd3b000`, re-read 27400 bytes, revalidated DEX 038/header/endian/map/SHA-256, returned `raw_byte_reconstruction=true` and `exact_apk_content_match=true`. `include_data=false` was used for device proof because raw payload transport through the WebCodex channel was safety-blocked; CLI raw output remains implemented for validated candidates <=4 MiB. |
| ART DEX chunked export protocol | PASS(host synthetic + device provider-self stream) | Runtime implements token-bound `art_export.open/chunk/close` for validated readable candidates up to 512 MiB with <=512 KiB chunks and per-chunk header/hash revalidation. API 36 provider-self exported the 27,400-byte runtime DEX in four chunks, verified every chunk hash, closed the token, and reconstructed SHA-256 `a74335727740c97c9617f2b8ff339b15801e1e4ed13e8286e0428e8a1a07688a`, byte-identical to installed APK `classes.dex`. Candidate discovery now reuses one maps snapshot; CLI export has a 20-second default runtime timeout because device open took about 6.76 s. |
| API 36 XmlBlock hidden peer mapping | PASS(device DEX/native binary analysis + provider-self runtime scan) | Provider reflection/JNI lookup hides `mNative`/`mParseState`, but the device's own `framework.jar/classes.dex` proves `XmlBlock.mNative:J` and `Parser.mParseState:J` exist with hidden-API flags; `newParser` calls `nativeCreateParseState` and `Parser.next` calls `nativeNext`. Device `libandroid_runtime.so` exposes a matching 22-entry JNINativeMethod table. Runtime offset-free table discovery passed provider-self with `table_found=true`, `method_count=22`, and all recovered function pointers inside executable segments; `register_symbol_resolved=false` under `RTLD_DEFAULT` does not affect table discovery. |
| Native XmlBlock/ResXMLTree isolated replay | PASS(device provider-self) | `memory.xml.block_probe` replayed the 340-byte APK-backed `autocrack_runtime_probe.xml` through discovered platform `nativeCreate` / `nativeCreateParseState` / `nativeNext` / destroy methods. Tree/parser creation succeeded, native events were `[2,3,1]`, END_DOCUMENT was reached, and the result explicitly keeps `existing_peer_reconstruction=false`. |
| Native XmlBlock event/attribute metadata | PASS(device provider-self) | Isolated replay now returns native namespace/name/text string-pool indices, source resource ID, attribute resource IDs, data types/data, string-value indices, and id/class/style metadata. The two probe attributes matched the Java resource semantics and END_DOCUMENT/destroy lifecycle still passed. |
| Native XmlBlock readable string-pool correlation | PASS(device provider-self) | Native replay indices were resolved through the exact 340-byte APK AXML string pool: START_TAG became `autocrack-probe`; Android attribute namespace/name resolved to `http://schemas.android.com/apk/res/android`, `name`/`version`; the string attribute resolved to `autocrack_runtime_probe`, while the integer attribute correctly retained no string value. |
| Existing XmlBlock hidden-peer Xposed capture | PASS(host build + provider-self negative + target positive) | Target-only hook records `XmlBlock.mNative` and `Parser.mParseState` through Xposed hidden-field access after `XmlBlock.newParser(int)`, with no ART offset guessing. Provider-self correctly returned `captured=false/xposed_required=true`; after the request-broadcast fallback, the owned target XML fixture `0x7f020000` returned `captured=true`, nonzero `native_tree`, nonzero `parse_state`, and `source_res_id=2130837504`. |

## Continuation host validation sweep

| Layer | Status | Evidence |
|---|---|---|
| Runtime/test Gradle rebuild | PASS(host) | `:autocrack-runtime:assembleDebug` and `:runtime-inspector-test-app:assembleDebug` passed after Stage 47 and capability-boundary updates using isolated `GRADLE_USER_HOME` to avoid the user's global Gradle init repository conflict. |
| Shared-runtime Toolpack packages | PASS(host) | Rebuilt `ui-inspect`, `runtime-inspect`, `memory-dump`, and `runtime-control`; ZIP SHA-256 values were `2a4f951161899ee97e083be291ae21ebdb0c4ea5da838efa40bd3ec9d3913b47`, `b3519b35796eda480bec20820b4671956415d9fb66d37c606c798b955ddd325b`, `26537ea49f9372c5054375a13774d08575e382c4e869a96f30ec26395685333c`, and `502f63851d7548cbbb32d3d4df7bf9560038f6d03ff39da31b01a04a83dc39b2`. |
| SimpleHook Toolpack package | PASS(host) | After the unified Runtime Test App migration, rebuilt `simplehook-toolpack-0.1.1.zip` with Toolpack SHA-256 `6020d6332f7b87640ca2c3e5813e1ec221e6d2234146d35e29cf63365f578e0a`, payload SHA-256 `1a8bb6b16b979cac8eec95abd343126501bba6148c744f5dfea5c466fe376996`, and payload size `43367`. |
| CLI entrypoints | PASS(host) | `ui-inspect --help`, `runtime-inspect --help`, `memory-dump --help`, `runtime-control --help`, and `simplehook --help` all exited successfully. |
| Target-device install/request validation | NOT EXECUTED(safety-blocked) | ADB and device `a4976c80` were visible, but direct install / target `content call` validation scripts were blocked by the OpenAI tool safety layer before execution; no target-process PASS is claimed from this continuation. |
| Toolpack manifest schema v2 sweep | PASS(host) | `ui-inspect`, `runtime-inspect`, `memory-dump`, `runtime-control`, and `simplehook` all generated `schemaVersion=2` manifests with `payloadEntry=payload.zip`, payload hash/size, commands, selfTests, `requires.runtime >=1.0.0`, and Android shell command requirements. |
| SimpleHook rule schema compatibility | PASS(host) | The Toolpack manifest moved to schema v2, while `simplehook rules validate toolpacks/simplehook/examples/replace-return-int.json --json` still passed against the unchanged rule `schema_version: 1` format. |
| Phase I cross-tool contract validator | PASS(host) | `python3 scripts/validate_cross_tool_contract.py --json` passed. It verified five maintained schema-v2 Toolpack manifests and the host-side prerequisites for the four required flows: coordinate pick to object inspect, listener to SimpleHook rule/log, ClassLoader to memory DEX, and WebView discovery/debug/eval/result. |
| Device verification bundle refresh | PASS(host bundle) | `bash -n scripts/prepare_device_verification_bundle.sh` passed, then a temporary bundle generation asserted the presence of 4 APKs, 5 maintained Toolpack manifests, `logs/cross-tool-contract.json`, and integration docs/scripts. |
| Runtime request broadcast fallback | PASS(host build + device replay) | Diagnosed target heartbeat present but `runtime-inspect` request timeout caused by AppsFilter blocking target-process provider discovery. Added API 34+ provider-to-target request broadcast with sender UID verification and target-to-module `runtime_complete` event fallback. `:autocrack-runtime:compileDebugJavaWithJavac` and `:autocrack-runtime:assembleDebug` passed; rebuilt runtime APK SHA-256 `f44066bc45647553788f21d71d04a62d304b46355fee203fb89cf6451d69a42b`. `scripts/verify_runtime_request_broadcast_on_device.sh` passed after LSPosed path refresh, and live `runtime.process` / XML probe target requests completed on device `a4976c80`. |
| Runtime target smoke after broadcast fallback | PASS(device) | Live target requests completed for `runtime.process` (PID `970`, uid `10338`), `runtime.activities` (one resumed `MainActivity`), `runtime.classloaders` (28 classloaders), `ui.windows` (one DecorView root), `ui.tree` (12 nodes), `ui.image` (DecorView PNG/base64 capture), and `ui.compose.tree` (valid zero-node response for a non-Compose fixture app). |
| SimpleHook rules broadcast fallback | PASS(host build + device grouped replay) | Added provider-to-target `com.luckylca.autocrack.runtime.RULES` broadcast, target receiver sender verification, channel-token override, broadcast-driven rule application, and dynamic ClassLoader snapshot refresh. `:autocrack-runtime:compileDebugJavaWithJavac` and Python script compile passed; rebuilt runtime APK SHA-256 `a2fc3ba7bea40b84208f8145a1134debe604725f64582ab295aa5bc86767cda9` installed on device `a4976c80`. |
| SimpleHook methods group | PASS(device) | `device_runtime_test.py --group methods --json` passed for `replace_return_int`, `replace_return_boolean`, `replace_return_string`, `replace_argument`, `skip_original`, `overload_int`, and `overload_string`. |
| SimpleHook logging group | PASS(device) | `device_runtime_test.py --group logging --json` passed for `record`, `before`, `after`, constructor logging, and exception logging. |
| SimpleHook fields group | PASS(device) | `device_runtime_test.py --group fields --json` passed for `static_field` and `instance_field`, with reset baseline before each field case. |
| SimpleHook delayed ClassLoader group | PASS(device) | `device_runtime_test.py --group delayed --json` passed: delayed rule moved from `WAITING_FOR_CLASS` to `ACTIVE` after dynamic DexClassLoader load and reload, and `loaded()` returned `loaded-hook`. |
| SimpleHook lifecycle group | PASS(device unified target) | Against `com.luckylca.runtimeinspector.testapp`, one grouped run passed `enable`, `disable`, `reload`, and `persistence` together. |
| SimpleHook monolithic matrix | PASS(device unified target) | Final `device_runtime_test.py --json` run completed with exit code `0`, top-level `ok=true`, and all 21 cases true on the single `com.luckylca.runtimeinspector.testapp` target. Final rule cleanup returned an empty rules list. |
| Toolpack schema v2 trust pinning | PASS(host unit) | `BuiltInToolpackTrustPolicy` now pins `schemaVersion` and `requires`. The built-in `simplehook` entry was updated to the current v2 manifest hashes and requirements. `SimpleHookToolpackTrustPolicyTest` and `ToolpackPackageManifestTest` passed offline with isolated `GRADLE_USER_HOME`. |
| Shared-runtime Toolpack trust catalog | PASS(host unit) | Added pinned built-in trust entries for `ui-inspect`, `runtime-inspect`, `memory-dump`, and `runtime-control`, including schema-v2 `requires` declarations. `SharedRuntimeToolpackTrustPolicyTest` parsed the exact generated manifests and verified `requireTrusted`; mutating a required capability is rejected. |
| Stage 57 refreshed verification bundle | PASS(host bundle) | Rebuilt `:app:assembleDebug` and `:autocrack-runtime:assembleDebug`; App APK SHA-256 `c396083424b30e3d4f4081e0ee93fcf8176fa367b0d0e23ce20dd0beb6865abf`, Runtime APK SHA-256 `a2fc3ba7bea40b84208f8145a1134debe604725f64582ab295aa5bc86767cda9`. Temporary bundle generation verified 4 APKs, 5 schema-v2 runtime Toolpack manifests, Stage 56 docs, replay scripts, and `cross-tool-contract.json ok=true`. |
| Stage 58 unified Runtime Test App | PASS(host + device) | Removed the separate `simplehook-test-app`; all Runtime/UI/Memory/SimpleHook fixtures now live in `runtime-inspector-test-app`. Unified APK SHA-256 `2eec000831906ed0e9948b06ebe70924311b59d1dc96ac3ac6be4552994a98cd`. Host unit/lint/assemble passed; device provider baseline, runtime request replay, all six SimpleHook groups, and the complete 21-case monolithic matrix passed. |
| Stage 59 final three-APK bundle | PASS(host bundle) | Final bundle contains exactly `AutoCrackApp-debug.apk`, `AutoCrackRuntime-debug.apk`, and `RuntimeInspectorTest-debug.apk`; no removed SimpleHook Test App artifact exists. App/Runtime/Test APK SHA-256 values are `091172158612b6cb42bd483f55a359e06416d671f06b205e46c0a37a0f18fa85`, `a2fc3ba7bea40b84208f8145a1134debe604725f64582ab295aa5bc86767cda9`, and `2eec000831906ed0e9948b06ebe70924311b59d1dc96ac3ac6be4552994a98cd`; bundled cross-tool contract remains `ok=true`. |
| Stage 60 same-ID Runtime delivery | PASS(host + device) | Client creates one logical request UUID after a successful submit; target uses `inFlight`/`handledAt` duplicate suppression. Provider broadcast retries reuse the same stored request ID instead of creating a second mutation request. |
| Stage 60 Binder Runtime channel | PASS(device) | Ordered broadcast is now a verified handshake that returns a target Binder endpoint; subsequent requests use Binder directly. Final E2E recorded `BINDER_CACHED=1`, `BINDER_DELIVERED=3`, `BINDER_DELIVERY_FAILED=0`. |
| Stage 60 Binder target-restart cleanup | PASS(device) | Provider registers `linkToDeath`; force-stop/restart changed target PID `15634 -> 17025`, logged `Runtime request Binder died; cache cleared`, and the first post-restart request completed without a stale Binder transaction failure. |
| Stage 60 MIUI Greeze compatibility | PASS(host + device) | Root `cmd greezer help` exposed `thuid`; device failure logs showed private Greeze blocking Binder/broadcast despite AOSP `isFrozen=false`. Shared clients now best-effort `thuid <target_uid> 1000` before and during requests. Automatic three-request replay passed with `client_target_thaws=2` per request. |
| Stage 60 Runtime client regression policy | PASS(host) | `test_runtime_client_retry.py` verifies exact `cmd greezer thuid 12345 1000`, read-only ambiguous-submit retry, mutation single-submit behavior, and thaw invocation. Four packaged Runtime clients and canonical `toolpacks/_shared/autocrack_runtime_client.py` are identical. |
| Stage 60 Pi-style Mobile Agent E2E | PASS(device) | Real `createMobileAgent -> exec_bash -> Debian RootFS` flow discovered six commands, read five `SKILL.md` files, confirmed root `android-shell`, and passed doctor/UI/process/memory/control/SimpleHook checks on `com.luckylca.runtimeinspector.testapp`. Final markers: `PI_AGENT_RUNTIME_TOOLPACK_E2E_OK`, `FINAL_PI_AGENT_E2E_PASS`, `REQUEST_ACCEPTED=4`, `REQUEST_GREEZER_DENIAL=0`. |
| Stage 60 final host/bundle sweep | PASS(host) | Python regression/compile, shell syntax, four Runtime Toolpack rebuilds, trust/manifest unit tests, App/Runtime/Test-App build gates, cross-tool contract, verification-bundle generation, and `git diff --check` passed. Current App/Runtime/Test APK SHA-256: `296e94743e6a6ba9d8ec009d8115e7e450d8a05430f839abc0ae64ed3e7068ac`, `5873ee5a28ec8b1542a500676b9cbc927520349fa49fc8242bf29bbb274afcf1`, `2eec000831906ed0e9948b06ebe70924311b59d1dc96ac3ac6be4552994a98cd`. |
