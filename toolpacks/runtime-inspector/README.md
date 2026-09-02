# Runtime Inspector 0.1.0

Runtime Inspector is an independent LSPosed/Xposed runtime View inspection tool for applications and devices you own or are authorized to test. It is intentionally separate from SimpleHook: SimpleHook handles Java method/field hooks; Runtime Inspector handles live windows, View trees, hit testing, listener metadata, and bounded UI actions.

## Components

- `runtime-inspector-runtime`: companion APK + LSPosed module.
- `runtime-inspector-test-app`: deterministic owned test UI.
- `runtime-inspector`: rootfs CLI toolpack command.

## Transport

CLI calls the companion ContentProvider. The companion sends an explicit permission-protected request broadcast to the scoped target package. Injected runtime code receives the request, executes it on the main thread, and sends an explicit result broadcast back to the companion. This avoids Android package-visibility restrictions that prevent arbitrary target apps from resolving the companion provider directly.

## Commands

```text
runtime-inspector status [--json]
runtime-inspector clear [--package PACKAGE] [--json]
runtime-inspector windows --package PACKAGE [--max-roots N] [--timeout SEC] [--json]
runtime-inspector tree --package PACKAGE [--listeners] [--max-nodes N] [--timeout SEC] [--json]
runtime-inspector at X Y --package PACKAGE [--listeners] [--include-hidden] [--json]
runtime-inspector action --package PACKAGE (--node-id ID | --x X --y Y) --action-json JSON [--json]
```

Supported first-phase actions: `set_visibility`, `set_text`, `set_text_color`, `set_background_color`, `set_alpha`, `remove_view`, `perform_click`, and `webview_eval_js`.

## Validation target

The device matrix verifies: module injection, root window enumeration, stable resource names, named click-listener discovery, coordinate hit testing, text mutation visible in the Android UI hierarchy, visibility mutation, and a second Dialog window.
