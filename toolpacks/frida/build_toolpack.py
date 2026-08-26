#!/usr/bin/env python3
import hashlib
import json
import os
import stat
import zipfile
from pathlib import Path

FIXED_TIME = (1980, 1, 1, 0, 0, 0)
EXECUTABLES = {
    "bin/frida-server-android",
    "bin/frida-autocrack-client",
    "libexec/frida_autocrack_client.py",
}


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def deterministic_zip(root: Path, output: Path) -> None:
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED) as archive:
        for path in sorted(p for p in root.rglob("*") if p.is_file()):
            relative = path.relative_to(root).as_posix()
            info = zipfile.ZipInfo(relative, FIXED_TIME)
            info.create_system = 3
            info.compress_type = zipfile.ZIP_STORED
            mode = 0o755 if relative in EXECUTABLES else 0o644
            info.external_attr = (stat.S_IFREG | mode) << 16
            archive.writestr(info, path.read_bytes())


def main() -> None:
    dist = Path("dist")
    payload = dist / "payload"
    payload_zip = dist / "payload.zip"
    deterministic_zip(payload, payload_zip)
    payload_hash = sha256(payload_zip)
    payload_size = payload_zip.stat().st_size

    version = os.environ["TOOLPACK_VERSION"]
    frida_version = os.environ["FRIDA_VERSION"]
    root = f"/opt/autocrack/toolpacks/packs/android-frida/{version}"
    java_url = (dist / "source/frida-java-bridge.source-url.txt").read_text().strip()
    java_sha = (dist / "source/frida-java-bridge.tgz.sha256").read_text().split()[0]

    manifest = {
        "schemaVersion": 1,
        "id": "android-frida",
        "title": "Android Frida bounded dynamic instrumentation",
        "version": version,
        "architecture": "arm64",
        "payloadEntry": "payload.zip",
        "payloadSha256": payload_hash,
        "payloadSizeBytes": payload_size,
        "requiredPaths": [
            "bin/frida-server-android",
            "bin/frida-autocrack-client",
            "libexec/autocrack-frida-agent.js",
            "libexec/frida_autocrack_client.py",
        ],
        "commands": [
            {"name": "frida-server-android", "relativePath": "bin/frida-server-android"},
            {"name": "frida-autocrack-client", "relativePath": "bin/frida-autocrack-client"},
        ],
        "selfTests": [
            {
                "id": "frida-server-android-binary",
                "title": "Official Android ARM64 Frida server payload",
                "command": f'test -x {root}/bin/frida-server-android && printf "AUTOCRACK_FRIDA_SERVER_BINARY_OK\\n"',
                "expectedExitCodes": [0],
                "outputContains": ["AUTOCRACK_FRIDA_SERVER_BINARY_OK"],
            },
            {
                "id": "frida-python-import",
                "title": "ARM64 Frida Python binding import",
                "command": f'PYTHONPATH={root}/python python3 -c "import frida; print(frida.__version__)"',
                "expectedExitCodes": [0],
                "outputContains": [frida_version],
            },
            {
                "id": "frida-bounded-client-help",
                "title": "Bounded AutoCrack Frida client",
                "command": f"{root}/bin/frida-autocrack-client --help",
                "expectedExitCodes": [0],
                "outputContains": ["native-trace"],
            },
        ],
        "sources": [
            {
                "name": "frida-server-android-arm64",
                "version": frida_version,
                "url": f"https://github.com/frida/frida/releases/download/{frida_version}/frida-server-{frida_version}-android-arm64.xz",
                "sha256": os.environ["FRIDA_SERVER_SHA256"],
            },
            {
                "name": "frida-python-aarch64",
                "version": frida_version,
                "url": f"https://pypi.org/project/frida/{frida_version}/",
                "sha256": os.environ["FRIDA_PYTHON_WHEEL_SHA256"],
            },
            {
                "name": "frida-java-bridge",
                "version": os.environ["FRIDA_JAVA_BRIDGE_VERSION"],
                "url": java_url,
                "sha256": java_sha,
            },
        ],
    }
    (dist / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    (dist / "payload.sha256").write_text(payload_hash + "\n", encoding="utf-8")
    (dist / "payload.size").write_text(str(payload_size) + "\n", encoding="utf-8")

    output = dist / "AutoCrackApp-android-frida-17.17.0-toolpack.zip"
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED) as archive:
        for name in ("manifest.json", "payload.zip"):
            path = dist / name
            info = zipfile.ZipInfo(name, FIXED_TIME)
            info.create_system = 3
            info.compress_type = zipfile.ZIP_STORED
            info.external_attr = (stat.S_IFREG | 0o644) << 16
            archive.writestr(info, path.read_bytes())
    toolpack_hash = sha256(output)
    (dist / f"{output.name}.sha256").write_text(f"{toolpack_hash}  {output.name}\n", encoding="utf-8")
    print(f"FRIDA_PAYLOAD_SHA256={payload_hash}")
    print(f"FRIDA_PAYLOAD_SIZE={payload_size}")
    print(f"FRIDA_TOOLPACK_SHA256={toolpack_hash}")
    print(f"FRIDA_JAVA_BRIDGE_TGZ_SHA256={java_sha}")


if __name__ == "__main__":
    main()
