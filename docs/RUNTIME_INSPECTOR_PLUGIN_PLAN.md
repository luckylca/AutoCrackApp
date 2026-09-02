# Runtime Inspector Plugin Plan

This document defines how to port Layout-Inspect-like runtime UI/class inspection into AutoCrackApp as a clean-room plugin. It must not copy closed APK code. It should implement the underlying Android/Xposed mechanisms directly.

## Why this belongs in AutoCrackApp

AutoCrackApp already has the key runtime pieces:

```text
simplehook-core
simplehook-runtime
simplehook-runtime Xposed entry
XSharedPreferences rule transport
ordered-broadcast runtime event channel
toolpack CLI packaging
owned SimpleHook test app
```

The missing piece is not another method-hook rule engine. The missing piece is a target-process runtime inspector that can answer:

```text
What screen/window is open?
What View tree exists now?
Which View is at this coordinate?
What class/id/text/listener does this View have?
Can a bounded authorized action hide/remove/change it?
Can that action become a persistent UI rule?
```

## Recommended module shape

Do not merge everything into `RuntimeEngine`. Add a sibling capability engine first:

```text
simplehook-runtime
  com.luckylca.simplehook.runtime
    SimpleHookXposedEntry
    RuntimeEngine                 # existing method/field hook engine
    RuntimeChannel                # existing transport
    InspectorEngine               # new runtime UI/class inspector
    WindowInspector               # root-window collection
    ViewTreeInspector             # tree snapshot + hit testing
    ViewActionExecutor            # one-shot UI actions
    ListenerInspector             # View listener reflection
    UiRuleEngine                  # persistent selector + action rules
```

Later, if this grows, split into new Gradle modules:

```text
runtime-inspector-core
runtime-inspector-runtime
runtime-inspector-toolpack
```

## Entry integration

Current entry flow:

```text
Application.attach(Context)
  -> new RuntimeEngine(context, packageName, processName, classLoader).start()
```

Change to:

```text
Application.attach(Context)
  -> RuntimeEngine.start()       # existing method hook rules
  -> InspectorEngine.start()     # new UI/class inspector commands
```

The engines can share `RuntimeChannel` and package/process identity.

## First command set

Add command transport independent from method-hook rules:

```text
runtime-inspector status --json
runtime-inspector inspect windows --package PKG --json
runtime-inspector inspect view-tree --package PKG --json
runtime-inspector inspect view-at --package PKG --x X --y Y --json
runtime-inspector inspect class CLASS --package PKG --json
runtime-inspector inspect members CLASS --package PKG --json
runtime-inspector action apply FILE --json
runtime-inspector rules add FILE --json
runtime-inspector rules list --package PKG --json
runtime-inspector logs --package PKG --json
```

The rootfs CLI can initially be implemented as a wrapper around the existing provider interface.

## Implementation: windows

Primary method:

```java
Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
Object global = wmg.getDeclaredMethod("getInstance").invoke(null);
Field views = wmg.getDeclaredField("mViews");
views.setAccessible(true);
List<View> roots = (List<View>) views.get(global);
```

Fallbacks:

```text
ActivityLifecycleCallbacks -> Activity.getWindow().getDecorView()
Hook WindowManager.addView/removeView -> maintain weak root registry
```

Use main thread when touching View objects.

## Implementation: view tree

For each root View:

```text
describe current View
if ViewGroup:
  iterate getChildCount()/getChildAt(i)
```

Collect bounded fields:

```text
class
node id
resource id / id name
visibility/shown/enabled/clickable/focusable
bounds on screen
width/height
text/hint/text size/text color for TextView
image drawable summary for ImageView
WebView URL/user-agent when accessible
listener class names
children
```

Hard cap nodes, strings, and result size.

## Implementation: coordinate hit testing

```java
Rect rect = new Rect();
if (view.getGlobalVisibleRect(rect) && rect.contains(x, y)) {
  candidates.add(view);
}
```

Sort candidates by:

```text
deeper View first
later draw order second
visible/shown first
```

Expose parent/child/previous/next navigation rather than pretending a coordinate always maps to one unambiguous View.

## Implementation: listener inspection

Use reflection on Android's View listener holder:

```java
Field infoField = View.class.getDeclaredField("mListenerInfo");
infoField.setAccessible(true);
Object info = infoField.get(view);

Class<?> type = Class.forName("android.view.View$ListenerInfo");
for (String name : listenerFields) {
  Field field = type.getDeclaredField(name);
  field.setAccessible(true);
  Object listener = field.get(info);
  if (listener != null) record(name, listener.getClass().getName());
}
```

Expected fields:

```text
mOnClickListener
mOnLongClickListener
mOnTouchListener
mOnKeyListener
mOnFocusChangeListener
```

Return partial results if a ROM/Android version blocks a field.

## Implementation: View actions

Supported v1 actions:

```text
set_visibility
remove_view
set_text
set_text_color
set_text_size_sp
set_padding
set_size
set_margin
webview_eval_js
webview_load_url
```

All mutate operations must be:

```text
package-scoped
explicitly requested
main-thread only
bounded
logged
reversible when practical
```

## Persistent UI rules

A UI rule is:

```text
trigger + selector + action
```

Example:

```json
{
  "schema_version": 1,
  "id": "hide_vip_button",
  "enabled": true,
  "package": "com.example.target",
  "trigger": {"type": "window_scan"},
  "selector": {
    "class": "android.widget.TextView",
    "text_contains": "VIP"
  },
  "actions": [
    {"type": "set_visibility", "value": "gone"}
  ]
}
```

Trigger sources:

```text
Application.attach initial scan
Activity resumed scan
WindowManager.addView scan
periodic low-frequency rescan while active rules exist
optional View attach listener for selected roots only
```

Do not hook every View constructor in v1.

## ClassLoader and class inspection

Reuse existing SimpleHook runtime behavior:

```text
ClassLoader.loadClass(String)
ClassLoader.loadClass(String, boolean)
BaseDexClassLoader.findClass(String)
BaseDexClassLoader constructors
```

Expose registry commands:

```text
list_classloaders
search_loaded_classes
inspect_class
inspect_class_members
```

Do not attempt ART heap-wide ClassLoader enumeration in v1. That is a native phase.

## Later native phase

Only after Java/View plugin is stable:

```text
maps: parse /proc/self/maps from target process
so list: dl_iterate_phdr or linker-aware enumeration
dlopen: explicit authorized Inject SO equivalent
dex: DexFile cookie/internal pointer mapping
xml/assets: Resources/AssetManager runtime recovery
```

Keep these as optional capabilities with stronger test gates.

## Test matrix

Add owned fixtures to `simplehook-test-app`:

```text
MainActivity with nested LinearLayout/TextView/Button/ImageView
Dialog fixture
PopupWindow fixture
RecyclerView fixture
WebView fixture
FLAG_SECURE test Activity, only for owned test app
Custom listener class fixture
Dynamic Dex loaded custom View fixture
```

Device test assertions:

```text
view tree returns expected nodes
view-at coordinate resolves correct Button/TextView
listener inspector reports owned listener class
set_text changes fixture TextView
set_visibility hides fixture View
remove_view removes fixture View
UI rule survives restart and reapplies
classloader registry sees dynamic Dex fixture
```

## First implementation milestone

Milestone 1 should stop at this closed loop:

```text
runtime-inspector inspect view-tree
runtime-inspector inspect view-at
runtime-inspector action set_text / set_visibility
runtime-inspector rules add
restart target
rule reapplies
device test PASS
```

That gives AutoCrackApp the most valuable Layout-Inspect-like capability without depending on closed implementation details.
