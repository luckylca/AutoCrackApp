# APKiD skill

Use this Toolpack for a fast first-pass identification of Android packers,
protectors, obfuscators, compilers, anti-analysis/RASP signals and the other
signatures provided by the upstream APKiD rule corpus.

This Toolpack intentionally preserves the complete upstream APKiD Python package,
CLI behavior and bundled rules. The `apkid` command is the upstream entrypoint;
AutoCrack does not replace it with a reduced scanner.

## First steps

```bash
apkid /workspace/app.apk
apkid -j /workspace/app.apk
apkid --help
```

Prefer `-j` when the result will be consumed by the Agent.

## Full upstream access

The entire upstream package is available through Python:

```bash
python3 - <<'PY'
import apkid
import apkid.apkid
import apkid.rules
print(apkid.__file__)
PY
```

Do not assume a finding is proof that a protection mechanism is active at
runtime. APKiD is a signature scanner; use JADX/Apktool, runtime-inspect,
memory-dump, Frida, LLDB or Rizin to verify the relevant behavior.

## Typical AutoCrack flow

1. Run `apkid -j APP` before expensive decompilation.
2. If no strong protection is detected, continue with `jadx` / `apktool`.
3. If a packer/protector is detected, inspect the reported files and select the
   appropriate runtime/memory/native workflow instead of blindly decompiling.
4. Keep the original APKiD output in `/workspace` when it is useful as evidence.

## Packaged upstream

- APKiD 3.1.0
- yara-python-dex 1.0.7 for Linux ARM64 / CPython 3.11

The package includes APKiD's upstream compiled YARA rule corpus
(`apkid/rules/rules.yarc`) and all upstream Python modules.
