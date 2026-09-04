# memory-dump skill

Use this CLI for bounded target maps, modules, native metadata, DEX, assets, APK entries, and XML extraction.

## First steps

```bash
memory-dump --help
runtime-inspect doctor --package PKG --json
memory-dump capabilities --package PKG --json
```

Prefer file-backed/APK-backed strategies before ART-memory reconstruction when both answer the question.

## DEX strategy

1. `dex-list` / `dex-apk-index` for discovery.
2. File-backed `dex-info/strings/classes/fields/methods/class-data` when available.
3. `dex-art-pointer-probe` only for version-gated ART discovery.
4. `dex-art-dump` for a validated candidate within the 4 MiB inline cap.
5. `dex-art-export --output FILE` for validated contiguous readable ART candidates up to 512 MiB.

Never treat `supported:false` as empty data. Arbitrary ART layouts, unreadable/non-contiguous mappings, and candidates above the export cap remain unsupported.
