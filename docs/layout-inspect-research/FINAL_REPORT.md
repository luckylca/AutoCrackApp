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

### Partially implemented / needs device proof

- UI root/window/tree/at/listeners/actions.
- View image strategies.
- Running Activity and ActivityThread snapshot.
- ClassLoader and DexFile class search.
- Object handle cross-tool loop.
- WebView list/debug/eval.
- FLAG_SECURE clear.
- Maps/modules/module dump.
- Dex dump via file-backed strategy.
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
- Consolidation commit: `feat: introduce shared layout inspect runtime toolpacks`; exact hash is intentionally not embedded because amending this file changes the hash. Use `git log -1` as source of truth.

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

These additions improve the rootfs CLI replacement path, but they are still not a complete native clone of Layout Inspect. ART Dex reconstruction, binary XmlBlock/ResXMLTree recovery, linker namespace bypass/dlopen internals, and Compose Semantics extraction remain explicitly version-gated or unsupported until implemented and verified on a real device.
