# memory-dump

`memory-dump` is the optional Layout-Inspect-style memory Toolpack. It is only a CLI surface over the shared AutoCrack Runtime provider `content://com.luckylca.autocrack.runtime`; it does not install a separate Xposed module.

## Runtime contract

- Requires the shared AutoCrack Runtime APK to be installed and LSPosed-scoped into the target package.
- Uses `android-shell` for host/rootfs communication.
- Emits deterministic JSON with `--json`.
- Large or version-specific native capabilities return explicit `supported:false` strategy objects instead of pretending that APK copying is an in-memory dump.

## Commands

```bash
memory-dump capabilities --package com.example.app --json
memory-dump maps --package com.example.app --max-maps 4096 --json
memory-dump modules --package com.example.app --filter libfoo.so --json
memory-dump read --package com.example.app 0x7000000000 128 --json
memory-dump module-dump --package com.example.app /data/app/.../libfoo.so --json
memory-dump dex-list --package com.example.app --json
memory-dump dex-list --package com.example.app --loader obj_loader --json
memory-dump dex-dump --package com.example.app obj_dex --json
memory-dump assets-list --package com.example.app res --json
memory-dump assets-pull --package com.example.app config/runtime.json --json
memory-dump xml-pull --package com.example.app 2131427356 --json
```

## Strategy boundaries

- Maps and modules are based on `/proc/self/maps` in the target process.
- Module dumps preserve mapping segment boundaries and never concatenate non-contiguous memory as one fake range.
- Dex dumping supports readable file-backed `DexFile` bytes and exposes cookie metadata for research; ART pointer reconstruction remains API/ABI-gated.
- Assets use runtime `AssetManager.list/open`, so they describe target-runtime-readable assets rather than only APK zip entries.
- XML pull currently uses logical `Resources.getXml` serialization; native `XmlBlock`/`ResXMLTree` byte recovery is reported as a separate unsupported strategy when unavailable.


## Dump controls

`module-dump`, `dex-dump`, and `assets-pull` expose `--max-bytes` so large outputs fail deterministically instead of flooding stdout. `dex-list --class-count` asks the target runtime to enumerate Dex entries up to its safety cap, which is useful when choosing the correct loader/Dex before dumping.


## APK entries

`memory-dump apk-entries --package PKG --prefix res/` enumerates base and split APK zip entries from the target runtime's `ApplicationInfo`. `memory-dump apk-pull --package PKG res/layout/example.xml --source base --max-bytes N` returns the raw APK entry bytes. This is useful for classes.dex, native libs, resources, and binary XML entries; it is not mislabeled as decoded XML.
