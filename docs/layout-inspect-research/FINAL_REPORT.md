# Layout Inspect → AutoCrackApp Migration Report

## 1. Layout Inspect research result

Research basis: `/Users/lucky/Desktop/project/LayoutInspectResearch`, including public APKs, jadx/apktool output, native library inventory, resources, evidence indexes, and existing analysis notes.

Key verified findings:

- Layout Inspect 1.1.5 exposes a floating runtime toolbox, not only a View inspector: application process, capture layout, activity records/manager, class loader, search class, Inject SO, Dump SO/Dex/XML/Maps/Assets, remove secure restriction, and browser debugging.
- The observable APK surface proves legacy and modern Xposed entries: `com.flass.layoutinspect.hook.Hook` and `ModernHook`, both pivoting into native-backed bootstrap code.
- The product is heavily protected/native-backed. The research workspace inventory shows 260 jadx Java files, 558 native method declarations, and 232 `registerNativesForClass` call sites through `Flassx`, `Flassxx`, `Flassxxx`, and `Flassxxxx` loaders.
- Because the protected stage-2 payload is not reconstructed as conventional source, exact original call bodies are not claimed. The AutoCrack implementation is a clean-room equivalent built from Android/Xposed runtime contracts and observable signatures/resources.

Important original-to-AutoCrack mapping:

| Original function | Original implementation confidence | AutoCrack implementation | Difference / limit |
|---|---:|---|---|
| App process info/kill | Surface confirmed | `runtime.process`, `control.process.kill` | Kill is explicit runtime control; host/root bridge needed for full process management |
| Window capture | Surface confirmed, mechanism strongly inferred | `WindowRegistry` + `WindowManagerGlobal.mViews` + one add/remove observer | Activity decor-only path intentionally avoided |
| View tree / hit test | Surface confirmed | `ui.tree`, `ui.at`; multi-root traversal; transform-aware candidate ranking | Current clipping precision is pragmatic, not a full rendering-engine replica |
| View props/listeners | Surface confirmed | `ui.props`, `ui.listeners`; public getters plus bounded reflection | Per-field reflection failures are reported, not hidden |
| View creation stack | Direct Throwable/View association evidence | `ViewCreationTracker` for constructors, inflate, addView | A pre-existing View cannot reveal historical construction stack unless tracker was active |
| View image | Surface confirmed | `View.draw(Canvas)`, `TextureView.getBitmap`, PixelCopy strategy where possible | Inline result capped; secure/surface failures reported |
| Runtime mutation | Surface confirmed | `ui.action`, `runtime-control` actions on main looper | Live-only; no APK rewrite |
| Activity records | Surface confirmed | lifecycle registry + `ActivityThread.mActivities` snapshot | Declared and live activities are separated |
| ClassLoader / class search | ART strategy evidence present | shared `ClassLoaderRegistry`; DexFile entries search | ART heap loader enumeration remains optional/native-gated |
| Object preview/dump | Surface confirmed | shared `ObjectRegistry`; cycle-safe JSON dump | Handles are process/package/PID/session-bound |
| Maps/SO | `MemoryUtil`, `/proc/self/maps`, soinfo strings | `memory.maps`, `memory.modules`, `memory.module.dump` | Module dumps preserve segment boundaries |
| Dex dump | mCookie/mInternalCookie/DexCaches modes confirmed | `memory.dex.list`, `memory.dex.dump`; file-backed strategy labeled | ART pointer reconstruction not implemented as fake success |
| XML/assets | Surface confirmed, asset constructor evidence | runtime `AssetManager` list/open and logical XML pull | native XmlBlock/ResXMLTree byte recovery remains gated |
| FLAG_SECURE | Surface confirmed | `control.secure.status`, `control.secure.disable` | Java Window flag only; secure SurfaceControl/DRM may remain |
| WebView debug/eval | WebView target and JS UI confirmed | `webview.list/info/debug/eval` | DevTools socket discovery remains optional |
| SystemUI | Scope design confirmed | package/process target is not hard-coded; `com.android.systemui` is allowed by contract | Device verification still required |
| Compose | Not proven in original | `ui.compose.status` identifies boundary | Semantics tree not claimed as complete |

## 2. Final architecture

```text
Agent
  -> exec_bash
    -> ui-inspect | runtime-inspect | memory-dump | runtime-control | simplehook | legacy runtime-inspector
      -> shared autocrack-runtime-client / android-shell
        -> content://com.luckylca.autocrack.runtime
          -> RuntimeRequestStore
            -> one AutoCrack Xposed entry in target process
              -> RuntimeDispatcher
                -> WindowRegistry / ActivityRegistry / ClassLoaderRegistry
                -> ObjectRegistry / ViewCreationTracker
                -> UiIntrospector / RuntimeIntrospector / MemoryIntrospector / WebControlIntrospector
                -> SimpleHook RuntimeEngine
```

The target process now has one shared AutoCrack runtime path instead of separate SimpleHook and Runtime Inspector Xposed modules.

## 3. Toolpacks

### ui-inspect

Commands: `status`, `clear`, `capabilities`, `windows`, `tree`, `at`, `props`, `listeners`, `stack`, `image`, `image-result`, `action`, `compose-tree`.

### runtime-inspect

Commands: `status`, `capabilities`, `process`, `activities`, `declared-activities`, `classloaders`, `class-search`, `class-describe`, `object`, `object-fields`, `object-dump`, `object-pin`, `object-release`, `object-clear-session`.

### memory-dump

Commands: `capabilities`, `maps`, `modules`, `read`, `module-dump`, `dex-list [--loader HANDLE]`, `dex-dump`, `assets-list`, `assets-pull`, `xml-pull`.

### runtime-control

Commands: `status`, `webview-list`, `webview-info`, `webview-debug`, `webview-eval`, `webview-eval-result`, `secure-status`, `secure-disable`, `so-inject`, `activity-start`, `process-kill`.

### simplehook

SimpleHook remains independent. It still owns method/constructor/field hook rules, logging, conditions, rule persistence, class inspection compatibility commands, and JSONL logs. It now points at the shared authority `com.luckylca.autocrack.runtime`.

### legacy runtime-inspector

The old first-phase `runtime-inspector` CLI remains compatible and forwards its window/tree/at/action workflow to the shared runtime authority.

## 4. Runtime

Implemented shared module:

- `autocrack-runtime`
- Xposed entry: `com.luckylca.autocrack.runtime.AutoCrackXposedEntry`
- Provider authority: `content://com.luckylca.autocrack.runtime`
- Dispatcher: `RuntimeDispatcher`

Shared registries/components:

- `ClassLoaderRegistry`
- `ObjectRegistry`
- `WindowRegistry`
- `ActivityRegistry`
- `ViewCreationTracker`
- `RuntimeRequestStore`
- `RuntimeThreading`
- `UiIntrospector`
- `RuntimeIntrospector`
- `MemoryIntrospector`
- `WebControlIntrospector`

Capability groups: `ui.*`, `runtime.*`, `object.*`, `memory.*`, `webview.*`, `control.*`, `hook.*`.

## 5. Object handle lifecycle

Handles are opaque `obj_*` identifiers created by the shared `ObjectRegistry`.

Lifecycle rules:

- WeakReference by default.
- Optional strong pin through `object.pin`.
- Process/package/PID/session binding.
- TTL and LRU cleanup.
- Maximum handle count and pin count.
- Explicit release and session clear.
- Stale/missing/type-mismatch errors are structured JSON errors.

This supports the required loop: `ui-inspect at -> View handle -> runtime-inspect object HANDLE -> same target-process registry object`.

## 6. Tests and validation

### Passed

- `git diff --check` passed.
- Android Gradle build passed with isolated `GRADLE_USER_HOME=.gradle-autocrack-runtime`:
  - `:autocrack-runtime:assembleDebug`
  - `:app:assembleDebug`
  - `:runtime-inspector-test-app:assembleDebug`
  - `:simplehook-test-app:assembleDebug`
- Gradle unit/build validation passed:
  - `:simplehook-core:test`
  - `:autocrack-runtime:testDebugUnitTest` (`NO-SOURCE`, compile path validated)
  - `:app:testDebugUnitTest`
- Python syntax validation passed with `compileall` for shared clients and CLIs.
- CLI `--help` smoke passed for `simplehook`, legacy `runtime-inspector`, `ui-inspect`, `runtime-inspect`, `memory-dump`, and `runtime-control`.
- JSON error envelope validation passed for runtime-dependent CLIs when the Android bridge is unavailable.
- SimpleHook file backend JSON success validation passed.
- Toolpack build passed for `android-host-shell`, `simplehook`, legacy `runtime-inspector`, `ui-inspect`, `runtime-inspect`, `memory-dump`, and `runtime-control`.
- Manifest/payload validation passed for legacy schema-v1 Toolpacks and new schema-v2 Toolpacks.
- `memory-dump dex-list --loader` help and payload validation passed.
- Stale capability/doc reference check passed.
- `android-host-shell` source executable bit and built payload executable mode validated.

### Blocked / not executed

- `pytest` was not executed because Python 3.14 and Python 3.13 on the current Runner do not have `pytest` installed.
- Real-device validation was not executed because the current Mac Runner did not expose an active Android host bridge. `android-shell --self-test` passes, but `android-shell id` exits 125 with bridge unavailable. No `adb` binary was on PATH.

## 7. Layout Inspect feature coverage

### Fully implemented or host-validated at code level

- Shared runtime module structure.
- One Xposed entry for dynamic Toolpacks.
- Runtime dispatcher and request/result channel.
- ObjectRegistry, ClassLoaderRegistry, WindowRegistry, ActivityRegistry.
- Toolpack manifest v2 for the four new Toolpacks.
- Shared Python Runtime Client.
- CLI command surfaces and JSON envelopes.
- SimpleHook authority migration to shared runtime.
- Legacy Runtime Inspector compatibility authority migration.
- UI relationship/search/control expansion: `ui.find`, `ui.parent`, `ui.children`, `ui.siblings`, expanded `ui.props`, expanded `ui.action`.
- Runtime object control expansion: `control.object.field.set`, `control.object.method.call`.
- Runtime Activity lifecycle metadata expansion.
- Memory/APK file-backed expansion: `memory.apk.entries`, `memory.apk.pull`, `memory.module.file_dump`.
- WebView navigation/control expansion: `webview.load_url`, `webview.reload`, `webview.go_back`, `webview.go_forward`, `webview.clear_cache`.

### Partially implemented / needs device proof

- UI root/window/tree/at/listeners/actions.
- View image strategies.
- Running Activity and ActivityThread snapshot.
- ClassLoader and DexFile class search.
- Object handle cross-tool loop.
- WebView list/debug/eval/navigation/control needs device proof.
- FLAG_SECURE clear.
- Maps/modules/process-memory segment dump needs device proof.
- Dex dump via file-backed/runtime strategy needs device proof.
- Runtime assets/XML logical pull.
- SystemUI target contract.

### Not claimed complete

- ART heap ClassLoader enumeration equivalent to Layout Inspect native strategy.
- ART DexFile pointer reconstruction for all API/ABI combinations.
- Native XmlBlock/ResXMLTree binary AXML recovery.
- Linker namespace bypass for SO injection.
- DRM/vendor secure surface bypass.
- Full Compose semantics tree extraction.
- Device matrix PASS for cross-tool loops, because the Android bridge was unavailable in this session.

## 8. Git

- Consolidation start HEAD: `e46c3e2e678edd380c62c3c23f21faea6ddf4238`.
- Branch: `codex/frida-capabilities-1.0.4`.
- Stage 1: `5bfaeede7d31d934b3b5a7f9917c85e32c230f0f` — `feat: introduce shared layout inspect runtime toolpacks`.
- Stage 2: `458c6c6` — `feat: expand layout inspect runtime controls`.
- Stage 3: `c2a4912` — `feat: enrich runtime activity records`.
- Stage 4: `949e92a` — `feat: add apk entry extraction for memory dump`.
- Stage 5: `79644f3` — `feat: extend webview runtime controls`.
- Stage 6: `cb479e2` — `feat: add file-backed module dumps`.
- Use `git log --oneline` for exact current short hashes after later amendments or rebases.

## 9. Remaining issues

1. Run the Android real-device matrix once the Mobile Agent / android-host-shell bridge is active.
2. Install the shared `autocrack-runtime` APK and verify LSPosed scope against the test apps.
3. Run SimpleHook full device regression after the shared authority migration.
4. Run cross-tool loops:
   - `ui-inspect at` -> `runtime-inspect object`
   - listener handle -> class describe -> SimpleHook rule -> click -> hook log
   - ClassLoader handle -> `memory-dump dex-list --loader` -> Dex dump -> jadx
   - WebView discovery -> debug enable -> JS eval
5. Add native/API-specific implementations only after a verified ART/linker/XML strategy exists. Until then, unsupported strategies must remain explicit.


## 10. Stage 2 continuation

After the first consolidation commit, the UI/runtime-control surface was extended toward the original Layout Inspect workflow rather than treating the initial architecture as complete. Added capabilities:

- `ui.find` for text/resource/class-name search across current runtime View roots.
- `ui.parent`, `ui.children`, and `ui.siblings` for handle-based View relationship navigation.
- Expanded `ui.props` for screen/window/local bounds, parent/root handles, state flags, scrolling, ViewGroup/TextView/ImageView/AdapterView/WebView details.
- Expanded `ui.action` with enable/clickable/focus/invalidate/text/hint/image/WebView aliases and mutation operations.
- `control.object.field.set` for bounded scalar field writes through ObjectRegistry handles.
- `control.object.method.call` for bounded reflected method invocation with exact argument types and scalar JSON arguments.
- `runtime.activities` now includes lifecycle timing/order metadata from the shared ActivityRegistry, improving Activity Records parity.
- `memory.apk.entries` and `memory.apk.pull` enumerate and extract base/split APK raw entries, covering file-backed classes.dex, res/*.xml, lib/*.so, and other packaged resources without falsely claiming decoded binary XML.
- `webview.load_url`, `webview.reload`, `webview.go_back`, `webview.go_forward`, and `webview.clear_cache` extend WebView control beyond eval/debug toggles.
- `memory.module.file_dump` adds an explicit file-backed SO/module copy path with SHA-256, kept separate from process-memory `memory.module.dump`.



## 11. Stage 7 native bridge continuation

After ADB was found at `/Users/lucky/Library/Android/sdk/platform-tools/adb`, device `a4976c80` was available and rooted. The shared runtime was extended with an AutoCrack JNI bridge packaged inside `autocrack-runtime` for `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`.

Added native-backed capabilities:

- `memory.native.probe`: controlled JNI self-test for bridge load, `process_vm_readv` self-read, `/proc/self/mem` fallback readiness, and `dladdr` symbol/file resolution.
- `memory.read`: native `process_vm_readv`/`pread` first, Java `/proc/self/mem` fallback second.
- `memory.module.dump`: segment dump now uses the native mapped-memory reader when available.
- `memory.dladdr`: native address resolution with `/proc/self/maps` fallback when `dladdr` cannot resolve a mapping start address.
- `control.so.dlopen`: native `dlopen(path, flags)` alongside the existing `System.load` path.
- `memory.maps` filters: `path_contains` and `permissions_contains` reduce Binder payload size for large processes.
- `runtime_execute_self`: provider-side direct execution path used to validate runtime/native capabilities inside the runtime app process without relying on target-process LSPosed polling.

Device validation performed in the runtime app process:

- `runtime_execute_self(memory.capabilities)` returned `native_bridge.supported=true`.
- `runtime_execute_self(memory.native.probe)` returned `self_read_supported=true`, `self_read_strategy=process_vm_readv`, `marker_ok=true`, and `dladdr_supported=true`.
- `dladdr` resolved the probe symbol from `base.apk!/lib/arm64-v8a/libautocrack_runtime_native.so`.

Target-process validation status:

- LSPosed logs showed `AutoCrack Runtime attached` for `com.luckylca.runtimeinspector.testapp`, proving scope/zygisk attachment can work.
- The installed runtime APK path changed after reinstall, while LSPosed `modules_config.db` still pointed to an older `com.luckylca.autocrack.runtime` base APK path.
- Direct DB path update was not performed by the tool because system-level LSPosed database mutation was blocked by safety checks. Refresh the module path through LSPosed UI or reboot/refresh LSPosed before rerunning target-process device tests.
- Until that refresh, target-process tests may keep using stale module code even though provider-side native self-tests pass.

Still not claimed complete:

- ART DexFile/mCookie/DexCaches full native reconstruction.
- Native XmlBlock/ResXMLTree binary AXML recovery.
- Linker namespace bypass beyond normal `dlopen`/`System.load` paths.
- DRM/vendor secure-surface bypass.
- Compose Semantics tree extraction.


## 12. Stage 8 file-backed binary XML extraction

`memory.xml.binary` was added to close a practical part of the original Layout Inspect XML-dump workflow without falsely claiming native `XmlBlock` memory reconstruction.

Added capabilities:

- `memory.xml.binary` accepts either `entry` or `resource_id`.
- `resource_id` resolution uses `Resources.getValue(resourceId, TypedValue, true)` to find the packaged XML path.
- `entry` extraction returns raw APK-backed binary AXML bytes from base/split APK sources.
- `apk_package` and `apk_path` let the runtime provider inspect another installed APK or an explicit APK path when target-process polling is unavailable.
- `memory.apk.entries` and `memory.apk.pull` share the same `apk_package` / `apk_path` source selection.
- `res/xml/autocrack_runtime_probe.xml` was added to the runtime APK as a stable binary XML probe resource.

Device validation:

- `runtime_execute_self(memory.xml.binary)` extracted `res/xml/autocrack_runtime_probe.xml` from the installed runtime APK.
- Returned metadata included `binary_axml=true`, `memory_reconstruction=false`, `encoding=base64`, `size=340`, and SHA-256 `62c63b30f0e773b1511f223cbe6ff5c7e11e7fc983afa33839147530059c1400`.
- `runtime_execute_self(memory.apk.entries)` with prefix `res/xml` returned the probe XML entry.

Boundary:

This is file-backed APK binary XML extraction. It is useful for layouts, XML resources, manifests, and split APK resources, but it is still not native in-memory `XmlBlock` / `ResXMLTree` recovery.

These additions improve the rootfs CLI replacement path, but they are still not a complete native clone of Layout Inspect. ART Dex reconstruction, binary XmlBlock/ResXMLTree recovery, linker namespace bypass/dlopen internals, and Compose Semantics extraction remain explicitly version-gated or unsupported until implemented and verified on a real device.


## 13. Stage 9 native ELF loader module enumeration

`memory.native.modules` was added to improve the SO/module view beyond `/proc/self/maps` grouping.

Implemented:

- Native C++ `dl_iterate_phdr` bridge in `libautocrack_runtime_native.so`.
- Java bridge exposure through `NativeBridge.modules(...)`.
- Runtime capability `memory.native.modules`.
- `memory-dump native-modules --filter TEXT --max-modules N` CLI command.
- Manifest capability declaration for the memory-dump toolpack.

Device evidence:

- Installed runtime APK on device `a4976c80`.
- `runtime_execute_self(memory.capabilities)` reported `native_modules.supported=true`.
- `runtime_execute_self(memory.native.modules, filter=autocrack)` returned one module from the loader view:
  `/data/app/.../base.apk!/lib/arm64-v8a/libautocrack_runtime_native.so`.
- The returned record included `base`, `load_start`, `load_end`, `phdr_count=9`, and `load_segments=3`.

Boundary:

- This is loader PHDR enumeration, not a replacement for `/proc/self/maps`.
- Anonymous mappings, JIT regions, ashmem/memfd mappings, and non-ELF ranges remain maps-only.
- It does not implement linker namespace bypass; `control.so.dlopen` still reports linker failures directly.


## 14. Stage 10 ART Dex cookie probe

`memory.dex.art_probe` was added as a bounded ART/DexFile research probe.

Implemented:

- Runtime capability `memory.dex.art_probe`.
- `memory-dump dex-art-probe --class-count --max-dex N` CLI command.
- Optional `--no-context-loader` flag. By default, the probe includes `Context.getClassLoader()` so provider-side diagnostics can still run before the Xposed classloader registry is populated.
- Per-Dex record summary: loader handle/class, Dex element metadata, file-backed status, file length, reflected `mCookie`/`mInternalCookie`, cookie field count, and cookie value count.

Boundary:

- This is not a native ART memory Dex reconstruction.
- It intentionally reports `art_memory_reconstruction=false`.
- It is meant to expose API-specific cookie shape and backing-file state for later native ART offset work.

Validation:

- Host Gradle build PASS.
- `memory-dump` Toolpack rebuild PASS.
- CLI help PASS.
- Runtime APK install PASS.
- Direct device provider-self assertion was not re-run after the context-loader fix because the tool safety layer blocked the short `runtime_execute_self(memory.dex.art_probe)` command. This is recorded as not device-proven, not as a pass.


## 15. Stage 11 native dlsym symbol resolution

`control.so.dlsym` was added to complete a safer native SO inspection loop around `dlopen`.

Implemented:

- Native C++ `dlsym` bridge in `libautocrack_runtime_native.so`.
- Java bridge `NativeBridge.dlsym(...)`.
- Runtime capability `control.so.dlsym`.
- `runtime-control so-dlsym SYMBOL [--handle 0x...]` CLI command.
- Toolpack manifest capability declaration.

Device evidence:

- Installed the rebuilt runtime APK on device `a4976c80`.
- `runtime_execute_self(control.so.dlsym, symbol=dlopen)` resolved `dlopen` through `RTLD_DEFAULT`.
- Returned address: `0x77d932c01c`.

Safety/behavior boundary:

- This only resolves a symbol address.
- It explicitly returns `callable=false` and does not call the resolved native function.
- It does not bypass linker namespace restrictions. Explicit handle lookup depends on a handle returned by `control.so.dlopen` and Android linker policy.


## 16. Stage 12 android_dlopen_ext loader path

`control.so.android_dlopen_ext` was added as a native Android-linker loading path. This is closer to bionic's real loading entry than plain `dlopen`, but it still does not claim linker namespace bypass.

Implemented:

- JNI bridge calls `android_dlopen_ext(path, flags, android_dlextinfo*)`.
- Optional `ext_flags` are accepted as integer/hex values from the CLI.
- `ANDROID_DLEXT_USE_LIBRARY_FD` can be requested and opens the target path as a library fd before calling the linker.
- `ANDROID_DLEXT_USE_NAMESPACE` is explicitly rejected unless a real `android_namespace_t` pointer is available; this prevents falsely claiming namespace bypass.
- Runtime result includes `namespace_bypass=false` and a namespace note.

Validation:

- Host Gradle build passed with isolated Gradle user home because the user's global `~/.gradle/init.gradle` conflicts with settings-level repositories.
- `runtime-control` Toolpack rebuild passed.
- CLI help and manifest checks passed.
- Direct device self-test for loading `/system/lib64/liblog.so` through `runtime_execute_self(control.so.android_dlopen_ext)` was blocked by the tool safety layer, so a device PASS is not claimed for this capability.


## 17. Stage 13 file-backed ELF metadata parsing

`memory.elf.info` was added to inspect native libraries without executing them. It supports both normal file paths and APK-embedded native libraries such as `base.apk!/lib/arm64-v8a/libfoo.so`.

Implemented:

- ELF32/ELF64 header parsing.
- Little/big-endian identification.
- machine/type/entry/program-header/section-header metadata.
- PT_LOAD segment summary.
- PT_NOTE parsing for GNU Build-ID.
- `memory-dump elf-info` CLI with `--path`, `--entry`, `--apk-package`, `--apk-path`, and `--max-bytes`.

Validation:

- Host Gradle build passed.
- `memory-dump` Toolpack rebuild passed.
- CLI help and manifest checks passed.
- Device provider-self test parsed the installed runtime APK entry `lib/arm64-v8a/libautocrack_runtime_native.so`: `ELF64`, `EM_AARCH64`, `phnum=9`, `load_segments=3`, Build-ID `89d021ea6f492c3cbca67001f0d1f97d4541e0a9`, `bytes_read=412664`, `truncated=false`.


## 18. Stage 14 host output materialization for dump commands

`memory-dump` dump/pull commands now support `--output`, so extracted bytes are written to files instead of forcing large base64 blobs to remain in stdout.

Implemented:

- `read --output FILE`
- `module-file-dump --output FILE`
- `dex-dump --output FILE`
- `assets-pull --output FILE`
- `xml-binary --output FILE`
- `apk-pull --output FILE`
- `xml-pull --output FILE` writes logical XML text as UTF-8.
- `module-dump --output DIR` writes one file per segment plus `manifest.json`.

Validation:

- Python compile check passed.
- Help assertions passed for all output-enabled commands.
- Local unit checks verified base64 byte output, XML text output, and segmented module output.
- `memory-dump` Toolpack rebuild passed.
## 17. Stage 13 file-backed ELF header / Build-ID parsing

`memory.elf.info` was added for a bounded, non-executing ELF inspection path. It supports ordinary filesystem ELF paths and APK-embedded native libraries such as `base.apk!/lib/arm64-v8a/libfoo.so`. This complements `memory.native.modules` and `control.so.dlsym`: it inspects file-backed ELF metadata instead of executing code or enumerating already-loaded linker state.

Implemented coverage:

- Runtime capability: `memory.elf.info`.
- Toolpack command: `memory-dump elf-info`.
- Input sources: absolute file `path`, `apk_path + entry`, installed APK source selection, and `base.apk!/entry` style paths.
- ELF header fields: class, bits, endian, type, machine, entry, flags, EH/PH/SH sizes and counts.
- Program headers: all PHDR records with type/type_name, offset, vaddr, filesz, memsz, rwx flags, and alignment.
- LOAD/NOTE convenience arrays.
- GNU build-id note parsing when present.

Device evidence on `a4976c80` / API 36:

- `runtime_execute_self(memory.elf.info)` parsed `lib/arm64-v8a/libautocrack_runtime_native.so` from the installed runtime APK.
- Result: `ELF64`, `bits=64`, `machine=183 / EM_AARCH64`, `phnum=9`, `program_headers=9`, `load_segments=3`, `gnu_build_id=89d021ea6f492c3cbca67001f0d1f97d4541e0a9`.

Boundary:

- This is file-backed metadata parsing only. It does not replace runtime memory relocation reconstruction and does not bypass linker namespace restrictions.
## 18. Stage 14 bounded DEX magic/header scan

`memory.dex.scan` was added as a bounded DEX discovery probe. It scans selected readable maps for valid DEX header candidates and returns metadata only by default. This is deliberately separate from `memory.dex.art_probe`: `dex-art-probe` reflects Java `DexFile` objects and cookie shape, while `dex-scan` searches mapped bytes for DEX magic/header patterns.

Implemented coverage:

- Runtime capability: `memory.dex.scan`.
- Toolpack command: `memory-dump dex-scan`.
- Filters: `path_contains`, `max_maps`, `max_scan_bytes_per_map`, `max_candidates`, `include_anonymous`.
- Header checks: `dex\nNNN\0` magic, `file_size`, `header_size=0x70`, and endian tag.
- Optional bounded byte export through `--dump-bytes N --output DIR`, disabled by default.

Device evidence on `a4976c80` / API 36:

- `runtime_execute_self(memory.dex.scan, path_contains=base.apk)` executed successfully.
- Result was runnable but found no candidates in the sampled runtime APK mappings: `count=0`, `scanned_maps=2`, `skipped_maps=577`, `art_memory_reconstruction=false`.

Boundary:

- This is not ART `mCookie` / `DexCaches` reconstruction. On modern Android, app Dex may be represented through oat/vdex/art structures rather than a direct readable DEX mapping, so zero candidates is a valid result rather than proof that no classes exist.
## 19. Stage 15 file-backed ELF dynamic symbol parsing

`memory.elf.symbols` was added to parse bounded ELF symbol metadata from file-backed ELF inputs and APK-embedded native libraries. This complements runtime `control.so.dlsym` by exposing the static/dynamic symbol table view without resolving or invoking symbols in the target process.

Implemented coverage:

- Runtime capability: `memory.elf.symbols`.
- Toolpack command: `memory-dump elf-symbols`.
- Input sources match `memory.elf.info`: file path, `base.apk!/entry`, `apk_path + entry`, and installed APK source selection.
- Parses `.dynsym` by default, with optional `.symtab` through `--include-symtab`.
- Emits symbol name, table, section index, value, size, binding, type, visibility/other, and shndx.
- Supports `--filter` and `--max-symbols` for bounded output.

Device evidence on `a4976c80` / API 36:

- `runtime_execute_self(memory.elf.symbols, filter=Java_com_luckylca)` parsed the APK-embedded `lib/arm64-v8a/libautocrack_runtime_native.so`.
- Result: `.dynsym` available, `count=7`, including JNI exports such as `NativeBridge_nativeDlopen`, `NativeBridge_nativeDladdr`, `NativeBridge_nativeProbe`, `NativeBridge_nativeModules`, and `NativeBridge_nativeAndroidDlopenExt`.

Boundary:

- This is file-backed symbol metadata parsing only. It does not call function pointers and does not defeat stripped symbols if neither dynsym nor symtab names are present.
## 20. Stage 16 file-backed ELF relocation parsing

`memory.elf.relocations` was added to parse bounded ELF REL/RELA relocation metadata. This complements `memory.elf.symbols` and runtime `control.so.dlsym` by exposing the static relocation view used by the dynamic linker.

Implemented coverage:

- Runtime capability: `memory.elf.relocations`.
- Toolpack command: `memory-dump elf-relocations`.
- Input sources match `memory.elf.info` and `memory.elf.symbols`.
- Parses `SHT_RELA` and `SHT_REL` relocation sections.
- Emits relocation kind, offset, raw info, symbol index/name when linked tables are present, relocation type/name, and addend for RELA.
- Supports `--filter` and `--max-relocations` for bounded output.

Device evidence on `a4976c80` / API 36:

- `runtime_execute_self(memory.elf.relocations)` parsed the APK-embedded `lib/arm64-v8a/libautocrack_runtime_native.so`.
- Result returned `count=64`, `truncated=true` under the bounded test cap, with AArch64 relocation names such as `R_AARCH64_RELATIVE`.

Boundary:

- This parses file-backed relocation metadata only. It does not apply relocations, patch GOT/PLT, or bypass linker namespace policy.
## 21. Stage 17 file-backed ELF dynamic table parsing

`memory.elf.dynamic` was added to parse bounded ELF dynamic table metadata. This closes another loader-analysis gap between static ELF inspection and runtime `dlopen` / `dlsym` behavior.

Implemented coverage:

- Runtime capability: `memory.elf.dynamic`.
- Toolpack command: `memory-dump elf-dynamic`.
- Input sources match the other ELF commands.
- Parses `SHT_DYNAMIC` entries and labels common tags such as `DT_NEEDED`, `DT_SONAME`, `DT_RPATH`, `DT_RUNPATH`, `DT_STRTAB`, `DT_SYMTAB`, `DT_RELA`, `DT_RELASZ`, `DT_JMPREL`, `DT_FLAGS`, and `DT_FLAGS_1`.
- Resolves string-valued dynamic entries through the linked string table when section headers are available.

Device evidence on `a4976c80` / API 36:

- `runtime_execute_self(memory.elf.dynamic)` parsed the APK-embedded `lib/arm64-v8a/libautocrack_runtime_native.so`.
- Result: `count=28`, `needed=[liblog.so, libdl.so, libm.so, libc.so]`, `soname=libautocrack_runtime_native.so`, `truncated=false`.

Boundary:

- This reads file-backed dynamic table metadata only. It does not alter linker state, load libraries, or bypass Android linker namespace policy.
## 22. Stage 18 file-backed Android binary XML decode

`memory.xml.axml_decode` was added to decode file-backed Android binary XML into a readable chunk and node stream. This builds on `memory.xml.binary`: the runtime can now pull raw AXML bytes from an APK and decode the string pool, resource map, start/end/text events, and typed attributes.

Implemented coverage:

- Runtime capability: `memory.xml.axml_decode`.
- Toolpack command: `memory-dump xml-axml-decode`.
- Input sources match `memory.xml.binary`: `resource_id`, direct APK entry, installed APK package, or explicit APK path.
- Decodes AXML string pool, resource map, chunk list, start tags, end tags, text nodes, attribute names, namespaces, resource IDs, raw values, typed string/int/boolean values, and bounded node/attribute counts.

Device evidence on `a4976c80` / API 36:

- `runtime_execute_self(memory.xml.axml_decode, entry=res/xml/autocrack_runtime_probe.xml)` decoded the installed runtime APK probe resource.
- Result: `string_count=6`, `resource_count=2`, `chunk_count=6`, `node_count=2`, root tag `autocrack-probe`, `android:name=autocrack_runtime_probe`, and `android:version=1`.

Boundary:

- This is file-backed AXML chunk decoding, not native in-memory `XmlBlock` / `ResXMLTree` recovery. It closes the practical readable XML path for APK-backed resources while keeping the native-memory gap explicit.
