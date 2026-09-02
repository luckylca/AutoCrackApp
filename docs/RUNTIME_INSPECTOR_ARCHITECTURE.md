# Runtime Inspector Architecture

Runtime Inspector is a standalone tool, not a SimpleHook feature.

## Separation of responsibilities

- SimpleHook: method/constructor/field hooks and structured hook logs.
- Runtime Inspector: live Android windows, View hierarchy inspection, coordinate hit testing, listener class discovery, and bounded UI mutation.

## Runtime path

```text
runtime-inspector CLI
  -> Runtime Inspector companion ContentProvider
  -> explicit permission-protected request broadcast to scoped target package
  -> LSPosed-injected InspectorEngine dynamic receiver
  -> WindowRootRegistry + InspectorPrimitives on the target main thread
  -> explicit result broadcast to companion InspectorResultReceiver
  -> provider result store
  -> CLI polling result
```

The broadcast transport is intentional. A direct target-process call to the companion provider fails on Android 16 when the target app cannot see the companion package because of package visibility. Explicit broadcast transport avoids requiring modifications to the inspected app manifest.

## Phase 1 implemented surface

- enumerate current root windows;
- flatten View tree with class/resource/text/bounds/state;
- optional listener class reflection;
- coordinate hit testing;
- `set_visibility`, `set_text`, `set_text_color`, `set_background_color`, `set_alpha`, `remove_view`, `webview_eval_js`.

## Test fixture

`runtime-inspector-test-app` contains stable resource IDs, a named click listener, nested Views, and a Dialog. The device matrix verifies actual UI state changes with `uiautomator`, not only JSON return values.

## Current device findings

1. A first implementation using `MODE_WORLD_READABLE` crashed on Android 16 (`SecurityException`). It was replaced with private companion storage.
2. A second implementation had the injected target process call the companion provider directly. Injection succeeded, but Android package visibility blocked provider resolution. The transport was redesigned to explicit broadcasts.
3. The current broadcast design builds successfully. A final device matrix run requires installing the latest rebuilt runtime APK.
