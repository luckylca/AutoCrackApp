#!/usr/bin/env python3
import hashlib
import json
from pathlib import Path
import shutil
import stat
import zipfile

FIXED_TIME = (1980, 1, 1, 0, 0, 0)
VERSION = "simplehook-0.1.1"
OUTPUT_NAME = "simplehook-toolpack-0.1.1.zip"
EXECUTABLES = {"bin/simplehook", "libexec/simplehook_cli.py"}


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def deterministic_zip(root, output):
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED) as archive:
        for path in sorted(item for item in root.rglob("*") if item.is_file()):
            relative = path.relative_to(root).as_posix()
            info = zipfile.ZipInfo(relative, FIXED_TIME)
            info.create_system = 3
            info.compress_type = zipfile.ZIP_STORED
            info.external_attr = (stat.S_IFREG | (0o755 if relative in EXECUTABLES else 0o644)) << 16
            archive.writestr(info, path.read_bytes())


def main():
    root = Path(__file__).resolve().parent
    dist = root / "dist"
    payload = dist / "payload"
    if dist.exists():
        shutil.rmtree(dist)
    for relative in ("bin/simplehook", "libexec/simplehook_cli.py", "schema/simplehook-rule-v1.schema.json",
                     "examples/replace-return-int.json", "README.md", "VERSION"):
        source = root / relative
        destination = payload / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, destination)
    payload_zip = dist / "payload.zip"
    deterministic_zip(payload, payload_zip)
    payload_hash = sha256(payload_zip)
    payload_size = payload_zip.stat().st_size
    pack_root = "/opt/autocrack/toolpacks/active/simplehook"
    manifest_data = {
        "schemaVersion": 1,
        "id": "simplehook",
        "title": "SimpleHook Android Java method debugger",
        "version": VERSION,
        "description": "Manage precise, persistent LSPosed/Xposed Java method debugging rules and structured logs for authorized Android test applications.",
        "architecture": "all",
        "payloadEntry": "payload.zip",
        "payloadSha256": payload_hash,
        "payloadSizeBytes": payload_size,
        "requiredPaths": ["bin/simplehook", "libexec/simplehook_cli.py", "schema/simplehook-rule-v1.schema.json", "README.md", "VERSION"],
        "commands": [{"name": "simplehook", "relativePath": "bin/simplehook", "description": "Manage Android Java method debug rules, inspect loaded classes, and query JSONL runtime logs."}],
        "selfTests": [
            {"id": "simplehook-help", "title": "SimpleHook CLI command surface", "command": f"{pack_root}/bin/simplehook --help", "expectedExitCodes": [0], "outputContains": ["rules", "inspect", "environment"]},
            {"id": "simplehook-schema-validation", "title": "SimpleHook v1 example rule validation", "command": f"SIMPLEHOOK_HOME=/tmp/simplehook-self-test {pack_root}/bin/simplehook rules validate {pack_root}/examples/replace-return-int.json --json", "expectedExitCodes": [0], "outputContains": ["\"valid\":true"]},
        ],
        "sources": [{"name": "simplehook-cli", "version": "0.1.1", "url": "https://github.com/luckylca/AutoCrackApp/tree/main/toolpacks/simplehook", "sha256": sha256(root / "libexec/simplehook_cli.py")}],
    }
    manifest = dist / "manifest.json"
    manifest.write_text(json.dumps(manifest_data, indent=2) + "\n", encoding="utf-8")
    output = dist / OUTPUT_NAME
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED) as archive:
        for path in (manifest, payload_zip):
            info = zipfile.ZipInfo(path.name, FIXED_TIME)
            info.create_system = 3
            info.compress_type = zipfile.ZIP_STORED
            info.external_attr = (stat.S_IFREG | 0o644) << 16
            archive.writestr(info, path.read_bytes())
    (dist / "payload.sha256").write_text(payload_hash + "\n", encoding="ascii")
    (dist / "payload.size").write_text(str(payload_size) + "\n", encoding="ascii")
    (dist / "manifest.json.sha256").write_text(f"{sha256(manifest)}  manifest.json\n", encoding="ascii")
    (dist / f"{OUTPUT_NAME}.sha256").write_text(f"{sha256(output)}  {OUTPUT_NAME}\n", encoding="ascii")
    print(f"TOOLPACK={output}")
    print(f"PAYLOAD_SHA256={payload_hash}")
    print(f"PAYLOAD_SIZE={payload_size}")
    print(f"TOOLPACK_SHA256={sha256(output)}")


if __name__ == "__main__":
    main()
