# Layout Inspect Feature Matrix

## Evidence policy

This matrix describes Layout Inspect 1.1.5 (`com.flass.layoutinspect`) from the
public APK in `/Users/lucky/Desktop/project/LayoutInspectResearch`. `CONFIRMED`
means the APK, Java signature, resource, or native binary directly proves the
claim. `STRONG` means the signatures and Android contract leave little room for
another implementation. `INFERRED` is an AutoCrack clean-room design choice.

The APK nativeizes 232 Java classes through four `registerNativesForClass`
families. The protected stage-2 business payload has not been reconstructed as
a conventional ELF, so this project does not claim exact source-level parity
where only a native declaration is observable.

| Surface | Original evidence | Original confidence | AutoCrack target |
|---|---|---:|---|
| Process list/info/kill | `module_window_app_process.xml`; runtime Xposed scope | CONFIRMED surface | runtime info in-process; host `/proc` and `am force-stop` control |
| All windows | capture layouts; injected process; window package | CONFIRMED surface, STRONG mechanism | `WindowManagerGlobal.mViews` plus one shared add/remove observer |
| View tree and pick | `hook/window/capture_layout/*`; parent/child/previous/next UI | CONFIRMED surface | transform-aware traversal over every root, ordered by window and draw order |
| View properties | `object/visualization/C1151t` overloads and typed editors | CONFIRMED typed object surface | public getters first, bounded reflection second |
| Listeners | menu/member browser plus listener-related classes | CONFIRMED surface | `View.mListenerInfo`, AdapterView and TextWatcher strategies |
| Creation stack | `C1103g.m2479a(Throwable, View)` | CONFIRMED association | optional constructor/inflate/add tracker with weak keys and bounded stacks |
| View image | two UI actions, modern and legacy | CONFIRMED surface | Canvas draw; PixelCopy fallback for attached windows/surface content |
| Runtime-only mutation | typed View/WebView editors | CONFIRMED surface | main-looper action dispatcher, no APK rewrite |
| Running activities | Activity visualization and bootstrap `Activity` callback | CONFIRMED surface | lifecycle callbacks plus `ActivityThread.mActivities` snapshot |
| Declared/start activity | Activity manager UI | CONFIRMED surface | PackageManager describe; host `am start` |
| Class loaders | `C1306p.m3484d`, `getClassLoadersOffset` | CONFIRMED ART strategy | shared observer registry; DexPath discovery fallback |
| Class search/preview | class/member UI and object operation menu | CONFIRMED surface | bounded DexFile entries plus registered loaders and reflection |
| Object preview/dump | generic object visualization and class member window | CONFIRMED surface | shared weak `ObjectRegistry`, cycle-safe JSON serializer |
| SO inject | absolute-path input | CONFIRMED surface | target `System.load`; linker namespace failure reported, never hidden |
| SO/maps dump | `MemoryUtil`, `/proc/self/maps` native string | CONFIRMED low-level primitives | structured maps parser and exact segment dump strategies |
| Dex dump | mCookie/mInternalCookie/DexCaches modes and `C1187N` offsets | CONFIRMED strategies | Java cookie strategy with API capability matrix; file-backed fallback is labeled |
| Runtime XML | XML path UI | CONFIRMED surface | runtime `Resources`/`AssetManager`; binary XML native strategy explicitly gated |
| Runtime assets | `C1289p(AssetManager, String)` | CONFIRMED mechanism | recursive `AssetManager.list/open`, preserving runtime-readable bytes |
| FLAG_SECURE | secure removal UI | CONFIRMED surface | enumerate roots/windows and call `Window.clearFlags`; surface limits reported |
| WebView | JS UI and `C1292s.setTarget(WebView)` | CONFIRMED | list/info/debug/eval on main looper; optional DevTools socket discovery |
| SystemUI | Xposed dynamic scope and system-server flag | CONFIRMED scope design | package/process is never hard-coded; `com.android.systemui` supported by contract |
| Compose | no proven original Compose semantics support | NOT PROVEN | identify `AndroidComposeView`; semantics capability reported separately |

## Coverage rule

An AutoCrack command is `supported` only when its selected strategy ran. A
fallback that copies an APK or reads a backing file is not labeled as an
in-memory Dex/XML/assets dump. Unsupported ART/linker/resource strategies return
`supported:false`, a reason, and the strategies considered.
