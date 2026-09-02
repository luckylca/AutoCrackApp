#!/usr/bin/env python3
import hashlib, json, shutil, stat, zipfile
from pathlib import Path

FIXED_TIME = (1980, 1, 1, 0, 0, 0)
TOOLPACK_ID = "ui-inspect"
VERSION = "ui-inspect-1.0.0"
OUTPUT = "ui-inspect-toolpack-1.0.0.zip"
EXEC = {"bin/ui-inspect", "libexec/ui_inspect_cli.py", "libexec/autocrack_runtime_client.py"}

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def zip_tree(root, output):
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED) as z:
        for path in sorted(p for p in root.rglob("*") if p.is_file()):
            rel = path.relative_to(root).as_posix()
            info = zipfile.ZipInfo(rel, FIXED_TIME)
            info.create_system = 3
            info.compress_type = zipfile.ZIP_STORED
            info.external_attr = (stat.S_IFREG | (0o755 if rel in EXEC else 0o644)) << 16
            z.writestr(info, path.read_bytes())

def main():
    root = Path(__file__).resolve().parent
    dist = root / "dist"
    payload = dist / "payload"
    if dist.exists(): shutil.rmtree(dist)
    for rel in ("bin/ui-inspect", "libexec/ui_inspect_cli.py", "libexec/autocrack_runtime_client.py", "README.md", "VERSION"):
        dst = payload / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(root / rel, dst)
    payload_zip = dist / "payload.zip"
    zip_tree(payload, payload_zip)
    pack_root = "/opt/autocrack/toolpacks/active/ui-inspect"
    manifest = {
        "schemaVersion": 2,
        "id": TOOLPACK_ID,
        "title": "AutoCrack UI Inspect",
        "version": VERSION,
        "description": "Inspect live Android windows, View trees, listeners, images and runtime-only View mutations.",
        "architecture": "all",
        "payloadEntry": "payload.zip",
        "payloadSha256": sha(payload_zip),
        "payloadSizeBytes": payload_zip.stat().st_size,
        "requiredPaths": ["bin/ui-inspect", "libexec/ui_inspect_cli.py", "libexec/autocrack_runtime_client.py", "README.md", "VERSION"],
        "commands": [{"name": "ui-inspect", "relativePath": "bin/ui-inspect", "description": "Inspect live Android windows, View trees, listeners, images and runtime-only View mutations."}],
        "selfTests": [{"id": "ui-inspect-help", "title": "AutoCrack UI Inspect CLI surface", "command": f"{pack_root}/bin/ui-inspect --help", "expectedExitCodes": [0], "outputContains": ["windows", "listeners", "compose-tree"]}],
        "sources": [{"name": "ui-inspect-cli", "version": "1.0.0", "url": "https://github.com/luckylca/AutoCrackApp/tree/main/toolpacks/ui-inspect", "sha256": sha(root / "libexec" / "ui_inspect_cli.py")}],
        "requires": {"runtime": ">=1.0.0", "capabilities": ["ui.windows", "ui.tree", "ui.at", "ui.find", "ui.props", "ui.parent", "ui.children", "ui.siblings", "ui.listeners", "ui.stack", "ui.image", "ui.action", "ui.compose.status", "object.describe"], "commands": ["android-shell"], "optionalCapabilities": ["ui.compose.tree"]}
    }
    manifest_path = dist / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    output = dist / OUTPUT
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED) as z:
        for path in (manifest_path, payload_zip):
            info = zipfile.ZipInfo(path.name, FIXED_TIME)
            info.create_system = 3
            info.compress_type = zipfile.ZIP_STORED
            info.external_attr = (stat.S_IFREG | 0o644) << 16
            z.writestr(info, path.read_bytes())
    (dist / f"{OUTPUT}.sha256").write_text(f"{sha(output)}  {OUTPUT}\n", encoding="ascii")
    print(f"TOOLPACK={output}")
    print(f"TOOLPACK_SHA256={sha(output)}")

if __name__ == "__main__": main()
