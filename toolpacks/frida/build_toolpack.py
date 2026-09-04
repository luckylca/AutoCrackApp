#!/usr/bin/env python3
import hashlib
import json
import os
import stat
import zipfile
from pathlib import Path

FIXED_TIME = (1980, 1, 1, 0, 0, 0)
FRIDA_TOOL_COMMANDS = [
    "frida",
    "frida-ls-devices",
    "frida-ps",
    "frida-kill",
    "frida-ls",
    "frida-rm",
    "frida-pull",
    "frida-push",
    "frida-discover",
    "frida-trace",
    "frida-strace",
    "frida-itrace",
    "frida-join",
    "frida-create",
    "frida-compile",
    "frida-pm",
    "frida-apk",
]
EXECUTABLES = {
    "bin/frida-server-android",
    "bin/android-frida-server",
    "bin/frida-autocrack-client",
    "libexec/frida_autocrack_client.py",
    "libexec/frida_tools_launcher.py",
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
            mode = 0o755 if relative in EXECUTABLES or relative.startswith("bin/") else 0o644
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
    root = "/opt/autocrack/toolpacks/active/android-frida"
    java_url = (dist / "source/frida-java-bridge.source-url.txt").read_text().strip()
    java_sha = (dist / "source/frida-java-bridge.tgz.sha256").read_text().split()[0]
    tools_version = os.environ["FRIDA_TOOLS_VERSION"]

    manifest = {
        "schemaVersion": 1,
        "id": "android-frida",
        "title": "Android Frida dynamic instrumentation",
        "version": version,
        "architecture": "arm64",
        "payloadEntry": "payload.zip",
        "payloadSha256": payload_hash,
        "payloadSizeBytes": payload_size,
        "requiredPaths": [
            "bin/frida-server-android",
            "bin/android-frida-server",
            "bin/frida-autocrack-client",
            "libexec/autocrack-frida-agent.js",
            "libexec/frida_autocrack_client.py",
            "libexec/frida_tools_launcher.py",
            "bin/frida",
            "bin/frida-ps",
            "bin/frida-trace",
            "SKILL.md",
        ],
        "commands": [
            {"name": "android-frida-server", "relativePath": "bin/android-frida-server"},
            {"name": "frida-autocrack-client", "relativePath": "bin/frida-autocrack-client"},
            *({"name": command, "relativePath": f"bin/{command}"} for command in FRIDA_TOOL_COMMANDS),
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
                "id": "android-frida-server-help",
                "title": "Android Frida server lifecycle helper",
                "command": "android-frida-server 2>&1 || test $? -eq 2",
                "expectedExitCodes": [0],
                "outputContains": ["start|status|stop"],
            },
            {
                "id": "frida-python-import",
                "title": "ARM64 Frida Python binding import",
                "command": 'PYTHONDONTWRITEBYTECODE=1 python3 -B -c "import frida; print(frida.__version__)"',
                "expectedExitCodes": [0],
                "outputContains": [frida_version],
            },
            {
                "id": "frida-upstream-cli-version",
                "title": "Upstream Frida CLI",
                "command": "frida --version",
                "expectedExitCodes": [0],
                "outputContains": [frida_version],
            },
            {
                "id": "frida-autocrack-client-help",
                "title": "Optional AutoCrack Frida helper",
                "command": f"{root}/bin/frida-autocrack-client --help",
                "expectedExitCodes": [0],
                "outputContains": ["native-trace", "tls-trace", "java-field-write"],
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
                "name": "frida-tools",
                "version": tools_version,
                "url": f"https://pypi.org/project/frida-tools/{tools_version}/",
                "sha256": os.environ["FRIDA_TOOLS_SDIST_SHA256"],
            },
            {
                "name": "colorama",
                "version": os.environ["COLORAMA_VERSION"],
                "url": f"https://pypi.org/project/colorama/{os.environ['COLORAMA_VERSION']}/",
                "sha256": os.environ["COLORAMA_WHEEL_SHA256"],
            },
            {
                "name": "prompt-toolkit",
                "version": os.environ["PROMPT_TOOLKIT_VERSION"],
                "url": f"https://pypi.org/project/prompt-toolkit/{os.environ['PROMPT_TOOLKIT_VERSION']}/",
                "sha256": os.environ["PROMPT_TOOLKIT_WHEEL_SHA256"],
            },
            {
                "name": "pygments",
                "version": os.environ["PYGMENTS_VERSION"],
                "url": f"https://pypi.org/project/Pygments/{os.environ['PYGMENTS_VERSION']}/",
                "sha256": os.environ["PYGMENTS_WHEEL_SHA256"],
            },
            {
                "name": "wcwidth",
                "version": os.environ["WCWIDTH_VERSION"],
                "url": f"https://pypi.org/project/wcwidth/{os.environ['WCWIDTH_VERSION']}/",
                "sha256": os.environ["WCWIDTH_WHEEL_SHA256"],
            },
            {
                "name": "websockets",
                "version": os.environ["WEBSOCKETS_VERSION"],
                "url": f"https://pypi.org/project/websockets/{os.environ['WEBSOCKETS_VERSION']}/",
                "sha256": os.environ["WEBSOCKETS_WHEEL_SHA256"],
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
