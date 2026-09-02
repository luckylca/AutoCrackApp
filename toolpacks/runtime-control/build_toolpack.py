#!/usr/bin/env python3
import hashlib, json, shutil, stat, zipfile
from pathlib import Path

FIXED_TIME = (1980, 1, 1, 0, 0, 0)
TOOLPACK_ID = "runtime-control"
VERSION = "runtime-control-1.0.0"
OUTPUT = "runtime-control-toolpack-1.0.0.zip"
EXEC = {"bin/runtime-control", "libexec/runtime_control_cli.py", "libexec/autocrack_runtime_client.py"}

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
    for rel in ("bin/runtime-control", "libexec/runtime_control_cli.py", "libexec/autocrack_runtime_client.py", "README.md", "VERSION"):
        dst = payload / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(root / rel, dst)
    payload_zip = dist / "payload.zip"
    zip_tree(payload, payload_zip)
    pack_root = "/opt/autocrack/toolpacks/active/runtime-control"
    manifest = {
        "schemaVersion": 2,
        "id": TOOLPACK_ID,
        "title": "AutoCrack Runtime Control",
        "version": VERSION,
        "description": "Start activities, kill processes, inject SOs, disable FLAG_SECURE and control WebView debugging/eval.",
        "architecture": "all",
        "payloadEntry": "payload.zip",
        "payloadSha256": sha(payload_zip),
        "payloadSizeBytes": payload_zip.stat().st_size,
        "requiredPaths": ["bin/runtime-control", "libexec/runtime_control_cli.py", "libexec/autocrack_runtime_client.py", "README.md", "VERSION"],
        "commands": [{"name": "runtime-control", "relativePath": "bin/runtime-control", "description": "Start activities, kill processes, inject SOs, disable FLAG_SECURE and control WebView debugging/eval."}],
        "selfTests": [{"id": "runtime-control-help", "title": "AutoCrack Runtime Control CLI surface", "command": f"{pack_root}/bin/runtime-control --help", "expectedExitCodes": [0], "outputContains": ["webview-debug", "secure-disable", "activity-start"]}],
        "sources": [{"name": "runtime-control-cli", "version": "1.0.0", "url": "https://github.com/luckylca/AutoCrackApp/tree/main/toolpacks/runtime-control", "sha256": sha(root / "libexec" / "runtime_control_cli.py")}],
        "requires": {"runtime": ">=1.0.0", "capabilities": ["control.activity.start", "control.process.kill", "control.so.inject", "control.so.dlopen", "control.so.dlsym", "control.secure.status", "control.secure.disable", "control.object.field.set", "control.object.method.call", "webview.list", "webview.info", "webview.debug", "webview.eval", "webview.clear_cache", "webview.go_forward", "webview.go_back", "webview.reload", "webview.load_url", "webview.eval.result"], "commands": ["android-shell"], "optionalCapabilities": ["webview.devtools_socket", "control.so.dlopen_namespace"]}
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
