#!/usr/bin/env python3
from __future__ import annotations

import argparse
import shutil
import sys
import tarfile
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SHARED = ROOT.parent / "_shared"
sys.path.insert(0, str(SHARED))

from python_wheel_toolpack import (  # noqa: E402
    copy_executable,
    extract_wheel,
    require_sha256,
    write_toolpack,
)

TOOLPACK_ID = "jnitrace"
UPSTREAM_VERSION = "3.3.1"
VERSION = "jnitrace-3.3.1_autocrack-1.0.0"

JNITRACE_SHA256 = "6fc6b39a561b34415250ddcc8eaa54a8d9414ca4f42532e909506493d471efed"
JNITRACE_URL = "https://files.pythonhosted.org/packages/00/d9/25136bf8b76a99c8f93843f75771d2b19b29004d322b94bf565773120c8b/jnitrace-3.3.1.tar.gz"
COLORAMA_SHA256 = "4f1d9991f5acc0ca119f9d443620b77f9d6b33703e51011c16baf57afb285fc6"
COLORAMA_URL = "https://files.pythonhosted.org/packages/d1/d6/3965ed04c63042e047cb6a3e6ed1a63a35087b6a609aa3a15ed8ac56c221/colorama-0.4.6-py2.py3-none-any.whl"
HEXDUMP_SHA256 = "d781a43b0c16ace3f9366aade73e8ad3a7bd5137d58f0b45ab2d3f54876f20db"
HEXDUMP_URL = "https://files.pythonhosted.org/packages/55/b3/279b1d57fa3681725d0db8820405cdcb4e62a9239c205e4ceac4391c78e4/hexdump-3.3.zip"
SETUPTOOLS_SHA256 = "062d34222ad13e0cc312a4c02d73f059e86a4acbfbdea8f8f76b28c99f306922"
SETUPTOOLS_URL = "https://files.pythonhosted.org/packages/a3/dc/17031897dae0efacfea57dfd3a82fdd2a2aeb58e0ff71b77b87e44edc772/setuptools-80.9.0-py3-none-any.whl"


def safe_extract_tar(archive: Path, target: Path) -> Path:
    target.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive, "r:gz") as source:
        members = source.getmembers()
        for member in members:
            parts = Path(member.name).parts
            if member.name.startswith("/") or ".." in parts:
                raise SystemExit(f"unsafe tar member: {member.name}")
        source.extractall(target)
    root = target / f"jnitrace-{UPSTREAM_VERSION}"
    if not root.is_dir():
        raise SystemExit(f"jnitrace sdist missing expected root: {root}")
    return root


def install_hexdump(archive: Path, python_root: Path) -> None:
    with zipfile.ZipFile(archive) as source:
        for name in ("hexdump.py", "PKG-INFO"):
            if name not in source.namelist():
                raise SystemExit(f"hexdump archive missing {name}")
        (python_root / "hexdump.py").write_bytes(source.read("hexdump.py"))
        egg_info = python_root / "hexdump-3.3.egg-info"
        egg_info.mkdir(parents=True, exist_ok=True)
        (egg_info / "PKG-INFO").write_bytes(source.read("PKG-INFO"))
        (egg_info / "top_level.txt").write_text("hexdump\n", encoding="utf-8")
        (egg_info / "dependency_links.txt").write_text("\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="Build full upstream jnitrace Toolpack")
    parser.add_argument("--jnitrace-sdist", required=True, type=Path)
    parser.add_argument("--colorama-wheel", required=True, type=Path)
    parser.add_argument("--hexdump-zip", required=True, type=Path)
    parser.add_argument("--setuptools-wheel", required=True, type=Path)
    parser.add_argument("--output-dir", type=Path, default=ROOT / "dist")
    args = parser.parse_args()

    require_sha256(args.jnitrace_sdist, JNITRACE_SHA256)
    require_sha256(args.colorama_wheel, COLORAMA_SHA256)
    require_sha256(args.hexdump_zip, HEXDUMP_SHA256)
    require_sha256(args.setuptools_wheel, SETUPTOOLS_SHA256)

    with tempfile.TemporaryDirectory() as temporary_directory:
        temporary = Path(temporary_directory)
        source_root = safe_extract_tar(args.jnitrace_sdist, temporary / "source")
        payload = temporary / "payload"
        python_root = payload / "python"
        python_root.mkdir(parents=True)

        shutil.copytree(source_root / "jnitrace", python_root / "jnitrace")
        shutil.copytree(source_root / "jnitrace.egg-info", python_root / "jnitrace.egg-info")
        extract_wheel(args.colorama_wheel, python_root)
        extract_wheel(args.setuptools_wheel, python_root)
        install_hexdump(args.hexdump_zip, python_root)

        copy_executable(ROOT / "bin" / "jnitrace", payload / "bin" / "jnitrace")
        shutil.copy2(ROOT / "SKILL.md", payload / "SKILL.md")
        shutil.copy2(ROOT / "VERSION", payload / "VERSION")
        for optional in ("README.md", "LICENSE"):
            source = source_root / optional
            if source.is_file():
                destination = payload / "upstream" / optional
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source, destination)

        for required in (
            python_root / "jnitrace" / "jnitrace.py",
            python_root / "jnitrace" / "build" / "jnitrace.js",
            python_root / "jnitrace.egg-info" / "PKG-INFO",
            python_root / "hexdump.py",
        ):
            if not required.is_file():
                raise SystemExit(f"missing upstream jnitrace runtime file: {required}")

        manifest = {
            "schemaVersion": 2,
            "id": TOOLPACK_ID,
            "title": "jnitrace complete JNI tracing client",
            "version": VERSION,
            "description": (
                "Complete upstream jnitrace 3.3.1 CLI/Python package and compiled "
                "jnitrace.js engine, reusing the production AutoCrack Frida Toolpack."
            ),
            "architecture": "arm64",
            "payloadEntry": "payload.zip",
            "payloadSha256": "0" * 64,
            "payloadSizeBytes": 1,
            "requiredPaths": [
                "bin/jnitrace",
                "python/jnitrace/jnitrace.py",
                "python/jnitrace/build/jnitrace.js",
                "python/jnitrace.egg-info/PKG-INFO",
                "python/hexdump.py",
                "SKILL.md",
                "VERSION",
            ],
            "commands": [
                {
                    "name": "jnitrace",
                    "relativePath": "bin/jnitrace",
                    "description": "Complete upstream jnitrace CLI.",
                }
            ],
            "selfTests": [
                {
                    "id": "jnitrace-version",
                    "title": "Upstream jnitrace CLI and Frida Python integration",
                    "command": "jnitrace --version",
                    "expectedExitCodes": [0],
                    "outputContains": [f"jnitrace {UPSTREAM_VERSION}"],
                },
                {
                    "id": "jnitrace-cli-surface",
                    "title": "Spawn attach remote filters and backtrace CLI surface",
                    "command": "jnitrace --help",
                    "expectedExitCodes": [0],
                    "outputContains": [
                        "--inject-method",
                        "--remote",
                        "--backtrace",
                        "--include",
                        "--exclude",
                        "--libraries",
                    ],
                },
                {
                    "id": "jnitrace-engine",
                    "title": "Upstream compiled JNI tracing engine",
                    "command": (
                        "test -s /opt/autocrack/toolpacks/active/jnitrace/"
                        "python/jnitrace/build/jnitrace.js && "
                        "printf 'AUTOCRACK_JNITRACE_ENGINE_OK\\n'"
                    ),
                    "expectedExitCodes": [0],
                    "outputContains": ["AUTOCRACK_JNITRACE_ENGINE_OK"],
                },
            ],
            "sources": [
                {
                    "name": "jnitrace-sdist",
                    "version": UPSTREAM_VERSION,
                    "url": JNITRACE_URL,
                    "sha256": JNITRACE_SHA256,
                },
                {
                    "name": "colorama-wheel",
                    "version": "0.4.6",
                    "url": COLORAMA_URL,
                    "sha256": COLORAMA_SHA256,
                },
                {
                    "name": "hexdump-sdist",
                    "version": "3.3",
                    "url": HEXDUMP_URL,
                    "sha256": HEXDUMP_SHA256,
                },
                {
                    "name": "setuptools-wheel",
                    "version": "80.9.0",
                    "url": SETUPTOOLS_URL,
                    "sha256": SETUPTOOLS_SHA256,
                },
            ],
            "requires": {
                "runtime": None,
                "capabilities": [],
                "commands": ["frida", "android-frida-server"],
                "optionalCapabilities": [],
            },
        }
        output = write_toolpack(
            payload_root=payload,
            manifest=manifest,
            output_dir=args.output_dir,
            filename=f"AutoCrackApp-{TOOLPACK_ID}-{VERSION}-toolpack.zip",
            executables={"bin/jnitrace"},
        )
        print(f"VERSION={VERSION}")
        print(f"OUTPUT={output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
