# APK / DEX static analysis skill

Use `jadx` and `apktool` for static Android inspection inside Debian.

## First steps

```bash
jadx --version
apktool --version
file /workspace/target.apk
```

## Typical workflow

- For focused code work, prefer JADX search or a single class before decompiling an entire large APK.
- For resources, manifest/smali, and rebuild-oriented inspection, use `apktool d`.
- Write output under `/workspace` and use `rg`/Python to reduce large result sets before returning them to the model.

The JADX wrapper defaults to a mobile-safe heap/thread budget (`AUTOC_JADX_HEAP_MB=768`, `AUTOC_JADX_THREADS=2`) unless explicitly overridden. Large decompilations can still be expensive; narrow the target whenever possible.
