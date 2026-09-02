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
ui-inspect props --package com.example.app obj_view --json
ui-inspect listeners --package com.example.app obj_view --json
ui-inspect stack --package com.example.app obj_view --json
ui-inspect image --package com.example.app obj_view --json
ui-inspect image-result --package com.example.app img_token --json
ui-inspect action --package com.example.app obj_view --action-json '{"type":"set_visibility","value":"gone"}' --json
ui-inspect compose-tree --package com.example.app --json
```

## Scope

This Toolpack owns Window roots, View tree traversal, hit testing, View properties, listener extraction, creation/inflate/add stack lookup, View-to-image strategies, live View mutation, and Compose boundary reporting. It returns real runtime object handles that can be passed to `runtime-inspect` and `simplehook` workflows.
