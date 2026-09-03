# ui-inspect

`ui-inspect` is the optional Layout-Inspect-style View Toolpack. It is only a CLI surface over the shared AutoCrack Runtime provider `content://com.luckylca.autocrack.runtime`; it does not install a separate Xposed module.

## Runtime contract

- Requires the shared AutoCrack Runtime APK to be installed and LSPosed-scoped into the target package.
- Uses `android-shell` for host/rootfs communication.
- Emits deterministic JSON with `--json`.
- All live View mutation and WebView/View image work is dispatched through the target main looper by the shared Runtime.

## Commands

```bash
ui-inspect capabilities --package com.example.app --json
ui-inspect windows --package com.example.app --json
ui-inspect tree --package com.example.app --listeners --json
ui-inspect at --package com.example.app 540 1200 --listeners --json
ui-inspect find --package com.example.app --text Login --json
ui-inspect props --package com.example.app obj_view --json
ui-inspect parent --package com.example.app obj_view --json
ui-inspect children --package com.example.app obj_container --max-children 128 --json
ui-inspect siblings --package com.example.app obj_view --json
ui-inspect listeners --package com.example.app obj_view --json
ui-inspect stack --package com.example.app obj_view --json
ui-inspect image --package com.example.app obj_view --json
ui-inspect image-result --package com.example.app img_token --json
ui-inspect action --package com.example.app obj_view --action-json '{"type":"set_visibility","value":"gone"}' --json
ui-inspect compose-tree --package com.example.app --max-nodes 512 --json
ui-inspect compose-tree --package com.example.app --merged --json
```

## Scope

This Toolpack owns Window roots, View tree traversal, hit testing, View properties, listener extraction, creation/inflate/add stack lookup, View-to-image strategies, live View mutation, and Compose boundary reporting. It returns real runtime object handles that can be passed to `runtime-inspect` and `simplehook` workflows.


## View search and relationships

`find` searches the current runtime View forest by text substring, resource-name substring, or class-name substring and returns real object handles. `parent`, `children`, and `siblings` provide relationship navigation for those handles so an Agent can move from a hit-test result to its container, neighboring controls, listeners, screenshot, or mutation command without re-parsing the entire tree.

## Mutation surface

`action` supports visibility, enable/clickable toggles, focus, invalidation, size, padding, margin, background color, TextView text/hint/color/size, ImageView clear/resource operations, WebView URL/user-agent/eval operations, and legacy aliases such as `perform_click`, `remove_view`, and `webview_eval_js`. Actions are temporary runtime mutations; they do not patch APK resources.

## Compose Semantics

`compose-tree` now requests `ui.compose.tree`, which reflectively probes `AndroidComposeView -> SemanticsOwner -> SemanticsNode` and returns best-effort node/config text, bounds, ids, and children. It does not fake Compose nodes as Android View children, and exact config fields remain Compose-version dependent.
