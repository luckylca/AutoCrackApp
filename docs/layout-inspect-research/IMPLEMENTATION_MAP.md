# Layout Inspect Implementation Map

## Bootstrap and protection

The legacy entry is `com.flass.layoutinspect.hook.Hook`; the modern entry is
`ModernHook`. Both store package/class-loader/module-path state and call native
`AbstractC1111o.m2480a()`. `AbstractC1111o` owns a main `Handler`, worker
`ExecutorService`, two themed target contexts, and an Activity callback. Native
work is split across `libflass{x,xx,xxx,xxxx}.so` for four ABIs.

Each large library decrypts an RC4 `.main`, extracts a second RC4+zlib payload,
and maps a custom serialized ELF-like image. Evidence is under
`LayoutInspectResearch/native`, `evidence/native`, and `evidence/disasm`.
Therefore exact protected call bodies are unavailable; declarations below are
the strongest reproducible implementation map.

## UI and object path

1. `Hook.handleLoadPackage` stores the target `packageName` and `ClassLoader`.
2. `AbstractC1111o.m2480a()` bootstraps the target-process runtime.
3. `C1160q` owns entry and capture windows (`C1237c`, `C1239e`, `C1210d`,
   `C1214h`, `C1217k`, `C1221o`) and current selection geometry `C1158p`.
4. `hook/window/capture_layout` handles touch selection and candidate
   navigation. It holds real `View` objects, not accessibility nodes.
5. `C1169E.setTarget(Object|Class)` opens the class/object member browser.
6. `hook/object/visualization/C1151t` dispatches typed editors for primitive,
   `Drawable`, `Uri`, `Map`, and other object values.
7. `AbstractC1112a..AbstractC1116e` add byte array, ClassLoader, Drawable,
   generic object, and Class/Object operations to contextual menus.

## Feature mechanisms

### Processes

- Visible feature: current/more processes, preview, kill.
- Execution: target-process runtime supplies its package/process/application;
  cross-process enumeration and kill necessarily cross the host boundary.
- AutoCrack: `/proc/<pid>/cmdline`, `status`, and maps through root; runtime
  object preview stays in the selected process; kill uses `am force-stop` for a
  package or `kill` for a validated PID.

### Windows and View tree

- Visible feature: Activity, Dialog, PopupWindow, menu, overlay, SystemUI roots.
- Android dependency: `WindowManagerGlobal`, `WindowManagerImpl`, `ViewRootImpl`,
  and `DecorView`. An Activity decor alone omits Dialog, PopupWindow, Toast-like
  app windows, overlays, and additional roots.
- AutoCrack: reflect `WindowManagerGlobal.mViews` and install exactly one
  `WindowManagerGlobal.addView/removeView` observer. Root descriptors include
  display id, layout params type/flags, token identity, focus, attachment and Z.

### Hit testing

- A raw `getLocationOnScreen()+width/height` rectangle is only a coarse bound.
- AutoCrack maps the screen point into each View's local coordinates with the
  inverse global transform, rejects non-invertible matrices, applies ancestor
  visibility/alpha/clipChildren/clipToPadding/scroll constraints, and traverses
  children in effective drawing order. It then ranks windows front-to-back and
  nodes by Z, drawing order, and depth. `SurfaceView` content remains a separate
  surface and is marked accordingly.

### Properties and listeners

- Common View properties use public APIs. Margins come from
  `ViewGroup.MarginLayoutParams`; `ImageView` source is `getDrawable()`;
  AdapterView exposes adapter/listeners; VideoView URI/headers require bounded
  field strategies; WebView uses `getUrl`, settings, cookies, and reflected JS
  interface maps where compatible.
- `View.ListenerInfo` is reached through private `View.mListenerInfo`, then
  `mOnClickListener`, `mOnLongClickListener`, `mOnTouchListener`,
  `mOnKeyListener`, and `mOnFocusChangeListener`. Xposed target-process code is
  not subject to the same app reflection path as a separate inspector process,
  but failures are still per-field results.
- Listener values are registered as object handles. Their class and declared
  methods are returned in SimpleHook-ready signature shape.

### Creation/inflate/add stacks

- Direct evidence: `C1103g.m2479a(Throwable, View)` associates a `Throwable`
  with a `View`; it is called from an Xposed callback family.
- A pre-existing View cannot reveal its constructor stack. AutoCrack therefore
  has an optional observer installed before app UI construction. It stores
  constructor, inflate, and add stacks in weak-key records, caps frame count and
  characters, and samples/rate-limits to avoid retaining Activity graphs.

### Images and mutation

- Layout Inspect exposes separate modern and legacy View-to-image actions.
- AutoCrack first renders ordinary views through `View.draw(Canvas)` into an
  ARGB bitmap. For window-backed pixels, video, `SurfaceView`, or hardware-only
  content it uses `PixelCopy` when API/window state permits and reports which
  strategy produced the artifact.
- All mutations execute on the target main looper. Width/height, padding,
  margin, drawable, TextView, ImageView, visibility/removal and WebView actions
  mutate only live objects.

### Activities

- `AbstractC1111o` has an Activity callback and object visualization supports
  Activity and Intent/Extras.
- AutoCrack combines weak `Application.ActivityLifecycleCallbacks` tracking with
  a reflected `ActivityThread.mActivities` snapshot. Declared activities come
  from PackageManager and are not presented as live instances.

### Class loaders, classes, and objects

- Direct evidence: `C1306p.m3484d()` returns an `ArrayList`; the same class has
  API-dependent static offsets and `getClassLoadersOffset()`. This proves an ART
  VM enumeration strategy, not just `Application.getClassLoader()`.
- Direct Dex evidence: `C1187N` targets `BaseDexClassLoader`, has
  `getDexCachesOffset()` and `getDexCachesOffsetFor32()`, accepts native
  addresses, and exposes UI modes mCookie/mInternalCookie/DexCaches.
- AutoCrack's stable path is a single registry populated from initial loaders,
  BaseDexClassLoader constructors, and class load/find callbacks. Search walks
  `pathList.dexElements[].dexFile.entries()` with exact/substring/regex filters
  and hard limits. In-memory/ART heap discovery is an optional versioned
  strategy, never silently assumed.
- Reflection descriptions include superclass, interfaces, fields,
  constructors, methods, inner classes, modifiers and signatures. Object dumps
  use identity cycle detection, depth/field/string/array budgets, and redact no
  fact about truncation.

### Memory, SO, Dex, XML, and assets

- `MemoryUtil` exposes address validation, protection, pointer-sized reading,
  and file dumping. `JniUtil` exposes JavaVM address and JNI-handle-to-object.
- Maps are parsed into start/end/perms/offset/device/inode/path. Contiguous dump
  means adjacent address ranges only; module dump retains segment boundaries.
- SO enumeration combines maps and, where native support is present,
  `dl_iterate_phdr`. Deleted and anonymous mappings remain distinct.
- SO injection runs in target process. `System.load(absolutePath)` is the Java
  strategy; namespace/ABI/SELinux/load errors are returned verbatim.
- Dex uses mCookie/mInternalCookie/DexCaches strategies. FileSize default/data/
  pointer modes are alternative interpretations of ART DexFile/header state,
  not equivalent to copying `base.apk`.
- `C1289p` directly receives `(AssetManager, String)`, confirming runtime asset
  traversal/open. Runtime XML requires `XmlBlock`/`ResXMLTree` native access for
  decrypted binary XML and is capability-gated by API/ABI.

### Secure windows and WebView

- FLAG_SECURE is a Window layout flag. AutoCrack finds associated Activity
  windows where possible, reports flags per root, and calls `clearFlags` on the
  main looper. Secure SurfaceControl/WebView/video paths may remain and are
  reported separately.
- `C1292s.setTarget(WebView)` and the Inject Javascript resource prove runtime
  WebView selection/evaluation. AutoCrack supports info, global debugging,
  main-thread `evaluateJavascript`, and optional DevTools socket/page listing.

## Data return

The original product renders an injected floating UI and writes some outputs to
files, so it does not need AutoCrack's cross-process command transport.
AutoCrack uses one companion provider, signed request records, explicit result
broadcasts, bounded polling, and artifact files for large results. Every result
is bound to package, process, PID, session, request id and nonce.
