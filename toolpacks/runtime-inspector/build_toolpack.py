#!/usr/bin/env python3
import hashlib, json, shutil, stat, zipfile
from pathlib import Path

FIXED_TIME = (1980, 1, 1, 0, 0, 0)
VERSION = "runtime-inspector-0.1.0"
OUTPUT = "runtime-inspector-toolpack-0.1.0.zip"
EXEC = {"bin/runtime-inspector", "libexec/runtime_inspector_cli.py"}


def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()


def zip_tree(root, output):
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED) as z:
        for path in sorted(p for p in root.rglob("*") if p.is_file()):
            rel = path.relative_to(root).as_posix(); info = zipfile.ZipInfo(rel, FIXED_TIME)
            info.create_system = 3; info.compress_type = zipfile.ZIP_STORED
            info.external_attr = (stat.S_IFREG | (0o755 if rel in EXEC else 0o644)) << 16
            z.writestr(info, path.read_bytes())


def main():
    root = Path(__file__).resolve().parent; dist = root / "dist"; payload = dist / "payload"
    if dist.exists(): shutil.rmtree(dist)
    for rel in ("bin/runtime-inspector", "libexec/runtime_inspector_cli.py", "README.md", "VERSION"):
        dst = payload / rel; dst.parent.mkdir(parents=True, exist_ok=True); shutil.copy2(root / rel, dst)
    payload_zip = dist / "payload.zip"; zip_tree(payload, payload_zip)
    manifest = {
        "schemaVersion": 1, "id": "runtime-inspector", "title": "Android Runtime View Inspector",
        "version": VERSION, "description": "Inspect live Android windows and View hierarchies in authorized LSPosed-scoped apps.",
        "architecture": "all", "payloadEntry": "payload.zip", "payloadSha256": sha(payload_zip), "payloadSizeBytes": payload_zip.stat().st_size,
        "requiredPaths": ["bin/runtime-inspector", "libexec/runtime_inspector_cli.py", "README.md", "VERSION"],
        "commands": [{"name": "runtime-inspector", "relativePath": "bin/runtime-inspector", "description": "Inspect live windows, Views, listeners, coordinates, and bounded UI actions."}],
        "selfTests": [{"id": "runtime-inspector-help", "title": "Runtime Inspector CLI surface", "command": "/opt/autocrack/toolpacks/active/runtime-inspector/bin/runtime-inspector --help", "expectedExitCodes": [0], "outputContains": ["windows", "tree", "action"]}],
        "sources": [{"name": "runtime-inspector-cli", "version": "0.1.0", "url": "https://github.com/luckylca/AutoCrackApp/tree/main/toolpacks/runtime-inspector", "sha256": sha(root / "libexec/runtime_inspector_cli.py")}]
    }
    manifest_path = dist / "manifest.json"; manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
    output = dist / OUTPUT
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED) as z:
        for path in (manifest_path, payload_zip):
            info = zipfile.ZipInfo(path.name, FIXED_TIME); info.create_system = 3; info.compress_type = zipfile.ZIP_STORED; info.external_attr = (stat.S_IFREG | 0o644) << 16
            z.writestr(info, path.read_bytes())
    (dist / f"{OUTPUT}.sha256").write_text(f"{sha(output)}  {OUTPUT}\n")
    print(f"TOOLPACK={output}"); print(f"TOOLPACK_SHA256={sha(output)}")


if __name__ == "__main__": main()
