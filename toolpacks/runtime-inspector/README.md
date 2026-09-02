# Runtime Inspector 0.1.0

Runtime Inspector is the legacy first-phase View inspection CLI for applications and devices you own or are authorized to test. In the Layout Inspect migration branch, it is kept for backward compatibility and forwards to the shared AutoCrack Runtime. SimpleHook still handles Java method/field hooks; Runtime Inspector handles live windows, View trees, hit testing, listener metadata, and bounded UI actions.

## Components

- `autocrack-runtime`: shared companion APK + LSPosed module.
- `runtime-inspector-test-app`: deterministic owned test UI.
- `runtime-inspector`: rootfs CLI toolpack command.

## Transport

CLI calls the shared AutoCrack Runtime ContentProvider. The provider writes bounded request records, injected runtime code polls them through `XSharedPreferences`, executes matching requests, and completes them through the provider. This preserves the old CLI surface while avoiding a second Xposed module.

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
