#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import shutil
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SHARED = ROOT.parent / "_shared"
sys.path.insert(0, str(SHARED))

from python_wheel_toolpack import (  # noqa: E402
    copy_executable,
    install_wheels,
    load_locked_wheelhouse,
    sha256,
    write_toolpack,
)

TOOLPACK_ID = "androguard"
UPSTREAM_VERSION = "4.1.4"
VERSION = "androguard-4.1.4_autocrack-1.0.0"


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Build the complete Androguard AutoCrack Toolpack"
    )
    parser.add_argument("--wheelhouse", required=True, type=Path)
    parser.add_argument("--output-dir", type=Path, default=ROOT / "dist")
    parser.add_argument(
        "--lock",
        type=Path,
        default=ROOT / "wheelhouse.lock.json",
    )
    args = parser.parse_args()

    lock = json.loads(args.lock.read_text(encoding="utf-8"))
    if lock.get("python") != "3.11":
        raise SystemExit("Androguard wheel lock must target Python 3.11")
    if lock.get("platform") != "manylinux2014_aarch64":
        raise SystemExit("Androguard wheel lock must target manylinux2014_aarch64")

    wheels, source_entries = load_locked_wheelhouse(args.lock, args.wheelhouse)
    primary = [
        entry
        for entry in source_entries
        if entry["name"].replace("_", "-").lower() == "androguard"
    ]
    if len(primary) != 1 or primary[0]["version"] != UPSTREAM_VERSION:
        raise SystemExit("wheel lock does not contain the pinned Androguard release")

    with tempfile.TemporaryDirectory() as temporary_directory:
        payload = Path(temporary_directory) / "payload"
        python_root = payload / "python"
        install_wheels(wheels, python_root)
        copy_executable(ROOT / "bin" / "androguard", payload / "bin" / "androguard")
        shutil.copy2(ROOT / "SKILL.md", payload / "SKILL.md")
        shutil.copy2(args.lock, payload / "WHEELHOUSE.lock.json")

        for required in (
            python_root / "androguard" / "__init__.py",
            python_root / "androguard" / "cli" / "cli.py",
            python_root / "androguard" / "core" / "apk" / "__init__.py",
        ):
            if not required.is_file():
                raise SystemExit(f"missing upstream Androguard file: {required}")

        manifest = {
            "schemaVersion": 2,
            "id": TOOLPACK_ID,
            "title": "Androguard complete Android static analysis API and CLI",
            "version": VERSION,
            "description": (
                "Complete Androguard 4.1.4 CLI and Python API with all pinned "
                "Linux ARM64 CPython 3.11 runtime dependencies."
            ),
            "architecture": "arm64",
            "payloadEntry": "payload.zip",
            "payloadSha256": "0" * 64,
            "payloadSizeBytes": 1,
            "requiredPaths": [
                "bin/androguard",
                "python/androguard/__init__.py",
                "python/androguard/cli/cli.py",
                "WHEELHOUSE.lock.json",
                "SKILL.md",
            ],
            "commands": [
                {
                    "name": "androguard",
                    "relativePath": "bin/androguard",
                    "description": "Complete upstream Androguard command group.",
                },
            ],
            "selfTests": [
                {
                    "id": "androguard-cli",
                    "title": "Complete upstream Androguard CLI",
                    "command": "androguard --help",
                    "expectedExitCodes": [0],
                    "outputContains": ["Usage:", "androguard"],
                },
                {
                    "id": "androguard-python-api",
                    "title": "Androguard high-level and resource Python APIs",
                    "command": (
                        "PYTHONDONTWRITEBYTECODE=1 python3 -B -c "
                        "\"import androguard;"
                        "from androguard.misc import AnalyzeAPK;"
                        "from androguard.core.apk import APK;"
                        "from androguard.core.axml import AXMLPrinter,ARSCParser;"
                        "print(androguard.__version__);"
                        "print('AUTOCRACK_ANDROGUARD_API_OK')\""
                    ),
                    "expectedExitCodes": [0],
                    "outputContains": [UPSTREAM_VERSION, "AUTOCRACK_ANDROGUARD_API_OK"],
                },
            ],
            "sources": [
                *[
                    {
                        "name": "wheel-" + entry["name"].replace("_", "-").lower(),
                        "version": entry["version"],
                        "url": entry["url"],
                        "sha256": entry["sha256"],
                    }
                    for entry in source_entries
                ],
                {
                    "name": "wheelhouse-lock",
                    "version": VERSION,
                    "url": (
                        "https://github.com/luckylca/AutoCrackApp/blob/main/"
                        "toolpacks/androguard/wheelhouse.lock.json"
                    ),
                    "sha256": sha256(args.lock),
                },
            ],
            "requires": {
                "runtime": None,
                "capabilities": [],
                "commands": [],
                "optionalCapabilities": [],
            },
        }
        output = write_toolpack(
            payload_root=payload,
            manifest=manifest,
            output_dir=args.output_dir,
            filename=f"AutoCrackApp-{TOOLPACK_ID}-{VERSION}-toolpack.zip",
            executables={"bin/androguard"},
        )
        print(f"WHEEL_COUNT={len(wheels)}")
        print(f"VERSION={VERSION}")
        print(f"OUTPUT={output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
