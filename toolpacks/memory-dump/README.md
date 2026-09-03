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


## Module file copy

`memory-dump module-file-dump --package PKG /path/to/libfoo.so --max-bytes N` returns a readable file-backed module copy with SHA-256. This is deliberately distinct from `module-dump`, which attempts process memory segments and preserves mapping boundaries.

## Native address lookup

`memory-dump dladdr --package PKG 0xADDR` uses the AutoCrack JNI bridge and native `dladdr` to resolve an address to its backing shared object and nearest exported symbol when the platform loader exposes that information.


### Native bridge probe

`memory-dump native-probe --package PKG --json` runs a controlled in-process JNI probe. It verifies that the AutoCrack native bridge is loaded, performs a self-read of a JNI-owned marker through `process_vm_readv` with `/proc/self/mem` fallback, and resolves the probe function through `dladdr`. This is a diagnostic capability; it does not read arbitrary target addresses.

`memory-dump maps --package PKG --path-contains TEXT --permissions-contains x --json` can filter mappings before returning them through Binder. Use this for large processes to avoid Binder transaction limits.


`memory-dump xml-block-probe --package PKG 0x7f... --json` opens a runtime `XmlResourceParser`, reflects the parser/`XmlBlock` field shape, records native-pointer-like fields, and emits a bounded XML event/attribute preview. This is an object-shape probe, not native `ResXMLTree` byte recovery.

`memory-dump xml-binary --package PKG --resource-id 0x7f... --json` resolves an XML resource to its APK entry and returns the raw file-backed binary AXML bytes. `--entry res/layout/foo.xml` can pull a known XML entry directly. This is explicitly not native XmlBlock memory reconstruction.

`memory-dump apk-entries --package RUNTIME_PKG --apk-package TARGET_PKG --prefix res/ --json` can enumerate another installed APK through `createPackageContext`, which is useful when provider-side diagnostics are available but target-process Xposed polling has not refreshed yet.

`memory-dump apk-entries --package RUNTIME_PKG --apk-path /data/app/.../base.apk --prefix res/ --json` reads an explicit APK path. This is useful when package visibility prevents `createPackageContext` from resolving `--apk-package`.

`memory-dump native-modules --package PKG --filter libc --json` uses the AutoCrack JNI bridge and `dl_iterate_phdr` to enumerate ELF loader modules with base/load ranges. This complements `/proc/self/maps`; it does not include anonymous or non-ELF mappings.

`memory-dump dex-art-probe --package PKG --class-count --json` summarizes each runtime DexFile, its backing path, class-count lower bound, and reflected ART `mCookie`/`mInternalCookie` shape. It is a probe for future native ART reconstruction, not a Dex memory reconstruction.

`memory-dump dex-art-pointer-probe --package PKG --max-pointers 32 --include-words --json` maps ART `mCookie`/`mInternalCookie` pointer values into `/proc/self/maps` and performs a bounded DEX magic/header neighborhood scan; `--include-words` adds a bounded 64-bit word table with map back-references and APK `classes*.dex` size correlation hints for ART layout research. It is a version-gated probe and still reports `art_memory_reconstruction=false`; `--try-layout-dex-header` is an explicit advanced opt-in for header-only heuristic probing and does not export bytes.

`dex-art-probe` includes the current `Context.getClassLoader()` by default so provider-side self diagnostics work even before the Xposed target-process registry is populated. Use `--no-context-loader` to restrict results to registered target classloaders only.

`memory-dump elf-info --package PKG --path /data/app/.../base.apk!/lib/arm64-v8a/libfoo.so --json` parses a bounded ELF header/program-header view and GNU build-id from a file-backed ELF or APK-embedded native library. It does not execute native code and does not replace runtime loader enumeration.

Dump/pull commands that return inline base64 now accept `--output PATH`. Single-object commands write bytes to `PATH` and omit the base64 blob from the printed result; `module-dump --output DIR` writes per-segment `.bin` files plus `manifest.json`. `xml-pull --output PATH` writes the logical XML text as UTF-8.

`memory-dump dex-scan --package PKG --path-contains base.apk --max-candidates 16 --json` performs a bounded readable-map scan for valid DEX magic/header candidates. It is a discovery probe only: by default it returns candidate metadata, not byte dumps, and it explicitly reports `art_memory_reconstruction=false`. Use `--dump-bytes N --output FILE_OR_DIR` only for controlled diagnostics.

`memory-dump elf-symbols --package PKG --entry lib/arm64-v8a/libfoo.so --filter JNI --json` parses bounded ELF `.dynsym` entries, and `.symtab` when `--include-symtab` is supplied. It reads file-backed metadata only and does not invoke resolved symbols.

`memory-dump elf-relocations --package PKG --entry lib/arm64-v8a/libfoo.so --filter dlopen --json` parses bounded ELF REL/RELA relocation records and links them to symbol names when the linked symbol/string tables are present. It does not apply relocations or execute native code.

`memory-dump elf-dynamic --package PKG --entry lib/arm64-v8a/libfoo.so --json` parses bounded ELF dynamic table metadata including `DT_NEEDED`, `DT_SONAME`, `DT_RPATH`, `DT_RUNPATH`, and linker table addresses. It reads file-backed metadata only.

`memory-dump xml-axml-decode --package PKG --entry res/xml/file.xml --json` decodes a file-backed Android binary XML string pool and start/end/text node stream. It is a readable AXML decoder, not native `XmlBlock` memory recovery.

`memory-dump xml-axml-text --package PKG --entry res/xml/file.xml --output out.xml` renders decoded file-backed AXML into readable XML text, including namespace declarations and typed attribute values when available. This is still APK-backed decoding, not native `XmlBlock` memory recovery.

`memory-dump dex-info --package PKG --entry classes.dex --json` parses a file-backed DEX header and map-list from an APK entry, APK embedded path, or readable `.dex` file. It does not reconstruct ART in-memory DexFile objects.

`memory-dump dex-strings --package PKG --entry classes.dex --filter luckylca --json` parses bounded DEX string table entries.

`memory-dump dex-classes --package PKG --entry classes.dex --filter com/luckylca --json` parses bounded DEX class definitions and resolves class/superclass/source descriptors from the file-backed DEX.

`memory-dump dex-fields --package PKG --entry classes.dex --filter someName --json` parses bounded DEX field_id records and resolves owner/type/name descriptors from file-backed DEX data.

`memory-dump dex-methods --package PKG --entry classes.dex --filter onCreate --json` parses bounded DEX method_id records, resolves proto return/parameter descriptors, and renders Dalvik-style method signatures from file-backed DEX data.

`memory-dump dex-class-data --package PKG --entry classes.dex --filter MyClass --json` parses bounded DEX `class_data_item` records, direct/virtual method lists, field lists, and `code_item` metadata such as register/ins/out sizes and `insns_size`. It does not disassemble bytecode and does not reconstruct ART memory.

`memory-dump dex-apk-index --package PKG --json` enumerates all `classes*.dex` entries in the target APK and returns bounded DEX header/map metadata for each entry. It is useful before choosing a specific `classesN.dex` for string/class/method inspection.
