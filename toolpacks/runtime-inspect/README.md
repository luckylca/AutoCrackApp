# runtime-inspect

`runtime-inspect` is the optional Layout-Inspect-style Java runtime Toolpack. It is only a CLI surface over the shared AutoCrack Runtime provider `content://com.luckylca.autocrack.runtime`; it does not install a separate Xposed module.

## Runtime contract

- Requires the shared AutoCrack Runtime APK to be installed and LSPosed-scoped into the target package.
- Uses `android-shell` for host/rootfs communication.
- Emits deterministic JSON with `--json`.
- Object handles are process-bound `obj_*` values owned by the shared `ObjectRegistry`; stale handles return structured errors.

## Commands

```bash
runtime-inspect capabilities --package com.example.app --json
runtime-inspect process --package com.example.app --json
runtime-inspect activities --package com.example.app --json
runtime-inspect declared-activities --package com.example.app --json
runtime-inspect classloaders --package com.example.app --json
runtime-inspect class-search --package com.example.app Activity --mode substring --json
runtime-inspect class-search --package com.example.app '^com\\.foo\\..*Activity$' --mode regex --json
runtime-inspect class-describe --package com.example.app com.foo.MainActivity --json
runtime-inspect object --package com.example.app obj_xxx --json
runtime-inspect object-fields --package com.example.app obj_xxx --json
runtime-inspect object-dump --package com.example.app obj_xxx --json
runtime-inspect object-pin --package com.example.app obj_xxx --json
runtime-inspect object-release --package com.example.app obj_xxx --json
runtime-inspect object-clear-session --package com.example.app ui --json
```

## Scope

This Toolpack owns process information, Activity records, declared Activity metadata, ClassLoader discovery, class search/preview, and generic object preview/dump. It intentionally does not perform method hooks; those stay in the independent `simplehook` Toolpack.


`runtime-inspect doctor --package PKG --json` runs a read-only provider/process/capability health summary across process, Activity, ClassLoader, memory capabilities, secure diagnostics, native/linker diagnostics, and Compose status. It does not modify LSPosed configuration, module paths, databases, or reboot state.
