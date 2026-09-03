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
| Runtime doctor | shared runtime/provider health surface | AUTOCRACK extension | read-only aggregate check for process, activities, classloaders, memory/native, secure, Compose and linker state; no LSPosed DB/path mutation |
| All windows | capture layouts; injected process; window package | CONFIRMED surface, STRONG mechanism | `WindowManagerGlobal.mViews` plus one shared add/remove observer |
| View tree and pick | `hook/window/capture_layout/*`; parent/child/previous/next UI | CONFIRMED surface | transform-aware traversal, hit-test, text/resource/class find, parent/children/siblings handles |
| View properties | `object/visualization/C1151t` overloads and typed editors | CONFIRMED typed object surface | public getters first, bounded reflection second |
| Listeners | menu/member browser plus listener-related classes | CONFIRMED surface | `View.mListenerInfo`, AdapterView and TextWatcher strategies |
| Creation stack | `C1103g.m2479a(Throwable, View)` | CONFIRMED association | optional constructor/inflate/add tracker with weak keys and bounded stacks |
| View image | two UI actions, modern and legacy | CONFIRMED surface | Canvas draw, TextureView bitmap and PixelCopy fallback; image results now include bounds/window/FLAG_SECURE/strategy diagnostics |
| Runtime-only mutation | typed View/WebView editors | CONFIRMED surface | main-looper action dispatcher, no APK rewrite |
| Running activities | Activity visualization and bootstrap `Activity` callback | CONFIRMED surface | lifecycle callbacks plus `ActivityThread.mActivities` snapshot |
| Declared/start activity | Activity manager UI | CONFIRMED surface | PackageManager describe; host `am start` |
| Class loaders | `C1306p.m3484d`, `getClassLoadersOffset` | CONFIRMED ART strategy | shared observer registry; DexPath discovery fallback |
| Class search/preview | class/member UI and object operation menu | CONFIRMED surface | bounded DexFile entries plus registered loaders and reflection |
| Object preview/dump | generic object visualization and class member window | CONFIRMED surface | shared weak `ObjectRegistry`, cycle-safe JSON serializer |
| SO inject | absolute-path input | CONFIRMED surface | target `System.load` plus JNI `dlopen`; linker namespace failure reported, never hidden |
| SO/maps dump | `MemoryUtil`, `/proc/self/maps` native string | CONFIRMED low-level primitives | structured maps parser, filtered maps return, native read-backed segment dump, dladdr fallback, and separate file-backed module copy with SHA-256 |
| Dex dump | mCookie/mInternalCookie/DexCaches modes and `C1187N` offsets | CONFIRMED strategies | Java cookie strategy + file-backed fallback; API-gated ART `data_begin`/size header-map reconstruction and bounded strings/classes/fields/methods/class_data parsing are device-validated; full raw byte reconstruction/export remains unsupported |
| Runtime XML | XML path UI | CONFIRMED surface | logical `Resources.getXml`, raw APK entry pull for `res/*.xml`; binary XmlBlock/ResXMLTree decode remains gated |
| Runtime assets | `C1289p(AssetManager, String)` | CONFIRMED mechanism | recursive `AssetManager.list/open`, preserving runtime-readable bytes |
| FLAG_SECURE | secure removal UI | CONFIRMED surface | `secure-status`, `secure-diagnose`, `secure-disable`; reports Window flags, post-clear status, and SurfaceView/TextureView/VideoView counts while keeping DRM/vendor secure-surface bypass unclaimed |
| WebView | JS UI and `C1292s.setTarget(WebView)` | CONFIRMED | list/info/debug/eval/result plus load/reload/back/forward/cache controls; rootfs DevTools socket discovery implemented, actual host port forwarding remains host-side |
| SystemUI | Xposed dynamic scope and system-server flag | CONFIRMED scope design | package/process is never hard-coded; `com.android.systemui` supported by contract |
| Compose | no proven original Compose semantics support | NOT PROVEN | identify `AndroidComposeView`; semantics capability reported separately |

## Coverage rule

An AutoCrack command is `supported` only when its selected strategy ran. A
fallback that copies an APK or reads a backing file is not labeled as an
in-memory Dex/XML/assets dump. Unsupported ART/linker/resource strategies return
`supported:false`, a reason, and the strategies considered.

| Native ELF loader modules | Complete for loader PHDR view; not a maps replacement | `memory.native.modules`, `memory-dump native-modules`; device self-test PASS on `a4976c80` |

| ART Dex cookie probe | Partial; host/install validated, not device-proven | `memory.dex.art_probe` exposes DexFile backing path and reflected cookie shape; `art_memory_reconstruction=false` |

| Native dlsym symbol lookup | Complete for bounded symbol lookup; no invocation | `control.so.dlsym`, `runtime-control so-dlsym`; device self-test PASS resolving `dlopen` through `RTLD_DEFAULT` |

| android_dlopen_ext loader | Host-complete; device assertion blocked | `control.so.android_dlopen_ext`, `runtime-control so-android-dlopen-ext`; explicit `namespace_bypass=false` until a real `android_namespace_t` strategy exists |

| ELF metadata / Build-ID | Complete for file-backed ELF/APK-entry parsing | `memory.elf.info`, `memory-dump elf-info`; parses headers, PT_LOAD, PT_NOTE GNU Build-ID without executing code; device self-test PASS |

| Dump output files | Complete for current base64/text dump commands | `memory-dump --output` writes single blobs, logical XML text, and module segment directories; prevents large base64-only workflows |
| File-backed ELF info | Complete for bounded file/APK ELF metadata | `memory.elf.info`, `memory-dump elf-info`; device self-test PASS on APK-embedded `libautocrack_runtime_native.so` |
| Bounded DEX memory scan | Partial; runnable probe, not ART reconstruction | `memory.dex.scan`, `memory-dump dex-scan`; device self-test PASS_RUNNABLE with zero candidates on sampled runtime APK mappings |
| File-backed ELF symbols | Complete for bounded dynsym/symtab metadata | `memory.elf.symbols`, `memory-dump elf-symbols`; device self-test PASS on JNI exports from runtime native library |
| File-backed ELF relocations | Complete for bounded REL/RELA metadata | `memory.elf.relocations`, `memory-dump elf-relocations`; device self-test PASS on AArch64 RELA records |
| File-backed ELF dynamic table | Complete for bounded dynamic metadata | `memory.elf.dynamic`, `memory-dump elf-dynamic`; device self-test PASS with `DT_NEEDED` and SONAME from runtime native library |
| File-backed Android binary XML decode | Complete for bounded APK-backed AXML chunk/node decode | `memory.xml.axml_decode`, `memory-dump xml-axml-decode`; device self-test PASS on runtime probe XML |
| AXML namespace/text rendering | Complete for bounded APK-backed readable XML | `memory.xml.axml_text`, `memory-dump xml-axml-text`; device self-test PASS with namespace events and rendered `android:*` attributes |
| DEX file metadata | Complete for bounded file/APK DEX header and map-list | `memory.dex.info`, `memory-dump dex-info`; device self-test PASS on runtime `classes.dex`; ART cookie probe device evidence updated |
| Stage 21 | `memory.dex.strings` | Parse file/APK DEX string table with optional filter | Implemented | File-backed only |
| Stage 21 | `memory.dex.classes` | Parse DEX class_def descriptors, superclass and source file | Implemented | File-backed only |
| Stage 22 | `memory.dex.fields` | Parse DEX field_id owner/type/name descriptors | Implemented | Host validated; device assertion blocked by tool safety |
| Stage 22 | `memory.dex.methods` | Parse DEX method_id + proto signatures | Implemented | Host validated; device assertion blocked by tool safety |
| Stage 23 | `memory.dex.class_data` | Parse file/APK DEX class_data members and code_item metadata | Implemented | Host/install validated; direct device assertion blocked by tool safety |
| Stage 24 | `memory.dex.apk_index` | Enumerate APK `classes*.dex` entries and parse each DEX header/map | Implemented | Device provider-self validated |
| Stage 21 | `memory.dex.strings` | Parse file/APK DEX string table with optional filter | Implemented | Device provider-self validated |
| Stage 21 | `memory.dex.classes` | Parse DEX class_def descriptors, superclass and source file | Implemented | Device provider-self validated |

- `ui.compose.tree`: reflective `SemanticsOwner` tree probing from `AndroidComposeView`, bounded SemanticsNode/config traversal, and merged/unmerged selection. Version-dependent; target-process availability still depends on LSPosed refresh.
- `memory.dex.art_pointer_probe`: ART `mCookie`/`mInternalCookie` pointer collection, AArch64 TBI untagging, maps resolution, bounded word-level layout hints, APK `classes*.dex` entry-size correlation, and explicit opt-in header-only heuristic probing. Not full ART memory DEX reconstruction.

- `memory.xml.block_probe`: runtime `Resources.getXml(resourceId)` object-shape probe with bounded event/attribute preview and APK-backed AXML entry metadata correlation (`size`, `compressed_size`, `crc`, `sha256`) without byte export; not native `ResXMLTree` byte recovery.

- `control.so.diagnose`: read-only native/linker diagnostic reporting JNI bridge health, linker modules, and libdl symbol visibility while keeping namespace bypass unimplemented.
