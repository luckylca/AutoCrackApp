#!/usr/bin/env python3
import hashlib
import json
import shutil
import stat
import zipfile
from pathlib import Path

FIXED_TIME = (1980, 1, 1, 0, 0, 0)
VERSION = "android-host-shell-1.0.3"
TOOLPACK_ID = "android-host-shell"
OUTPUT_NAME = f"AutoCrackApp-{TOOLPACK_ID}-{VERSION}-toolpack.zip"


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256(path: Path) -> str:
    return sha256_bytes(path.read_bytes())


def deterministic_zip(root: Path, output: Path) -> None:
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED) as archive:
        for path in sorted(p for p in root.rglob("*") if p.is_file()):
            relative = path.relative_to(root).as_posix()
            info = zipfile.ZipInfo(relative, FIXED_TIME)
            info.create_system = 3
            info.compress_type = zipfile.ZIP_STORED
            mode = 0o755 if relative == "bin/android-shell" else 0o644
            info.external_attr = (stat.S_IFREG | mode) << 16
            archive.writestr(info, path.read_bytes())


def write_outer(output: Path, manifest: Path, payload_zip: Path) -> None:
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED) as archive:
        for path in (manifest, payload_zip):
            info = zipfile.ZipInfo(path.name, FIXED_TIME)
            info.create_system = 3
            info.compress_type = zipfile.ZIP_STORED
            info.external_attr = (stat.S_IFREG | 0o644) << 16
            archive.writestr(info, path.read_bytes())


def main() -> None:
    root = Path(__file__).resolve().parent
    dist = root / "dist"
    payload = dist / "payload"
    if dist.exists():
        shutil.rmtree(dist)
    (payload / "bin").mkdir(parents=True)
    shutil.copy2(root / "bin/android-shell", payload / "bin/android-shell")
    shutil.copy2(root / "SKILL.md", payload / "SKILL.md")

    payload_zip = dist / "payload.zip"
    deterministic_zip(payload, payload_zip)
    payload_hash = sha256(payload_zip)
    payload_size = payload_zip.stat().st_size
    client_hash = sha256(root / "bin/android-shell")
    pack_root = f"/opt/autocrack/toolpacks/active/{TOOLPACK_ID}"

    manifest_data = {
        "schemaVersion": 1,
        "id": TOOLPACK_ID,
        "title": "Android host root shell bridge",
        "version": VERSION,
        "description": (
            "Run real Android host commands as root from Debian. Use android-shell for pm, am, cmd, "
            "dumpsys, settings, getprop, logcat, input and other Android-native commands. The host "
            "working directory mirrors Debian /workspace, and literal /workspace paths are mapped automatically."
        ),
        "architecture": "all",
        "payloadEntry": "payload.zip",
        "payloadSha256": payload_hash,
        "payloadSizeBytes": payload_size,
        "requiredPaths": ["bin/android-shell", "SKILL.md"],
        "commands": [
            {
                "name": "android-shell",
                "relativePath": "bin/android-shell",
                "description": (
                    "Execute a real Android host argv as root. Examples: android-shell pm list packages; "
                    "android-shell pm path PACKAGE; android-shell dumpsys package PACKAGE; complex "
                    "pipelines use android-shell sh -c '...'. Android-host /workspace paths map to Debian /workspace."
                ),
            }
        ],
        "selfTests": [
            {
                "id": "android-host-shell-client-self-test",
                "title": "Android host shell bridge client",
                "command": f"{pack_root}/bin/android-shell --self-test",
                "expectedExitCodes": [0],
                "outputContains": ["ANDROID_HOST_SHELL_CLIENT_OK"],
            }
        ],
        "sources": [
            {
                "name": "android-host-shell-client",
                "version": "1.0.3",
                "url": "https://github.com/luckylca/AutoCrackApp/blob/main/toolpacks/android-host-shell/bin/android-shell",
                "sha256": client_hash,
            }
        ],
    }
    manifest = dist / "manifest.json"
    manifest.write_text(json.dumps(manifest_data, indent=2) + "\n", encoding="utf-8")

    output = dist / OUTPUT_NAME
    write_outer(output, manifest, payload_zip)
    (dist / "payload.sha256").write_text(payload_hash + "\n", encoding="utf-8")
    (dist / "payload.size").write_text(str(payload_size) + "\n", encoding="utf-8")
    (dist / f"{output.name}.sha256").write_text(
        f"{sha256(output)}  {output.name}\n",
        encoding="utf-8",
    )
    print(f"TOOLPACK={output}")
    print(f"PAYLOAD_SHA256={payload_hash}")
    print(f"PAYLOAD_SIZE={payload_size}")
    print(f"CLIENT_SHA256={client_hash}")
    print(f"TOOLPACK_SHA256={sha256(output)}")


if __name__ == "__main__":
    main()
