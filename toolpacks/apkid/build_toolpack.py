#!/usr/bin/env python3
from __future__ import annotations

import argparse
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
    require_sha256,
    write_toolpack,
)

TOOLPACK_ID = "apkid"
APKID_VERSION = "3.1.0"
YARA_DEX_VERSION = "1.0.7"
VERSION = f"apkid-{APKID_VERSION}_yara-python-dex-{YARA_DEX_VERSION}_autocrack-1.0.0"

APKID_WHEEL = "apkid-3.1.0-py2.py3-none-any.whl"
APKID_SHA256 = "02e349865bc1005ae2beb27fbb58acdeabb56d1a60ce723c344cde1bb32896f8"
APKID_URL = "https://pypi.org/project/apkid/3.1.0/"

YARA_WHEEL = (
    "yara_python_dex-1.0.7-cp311-cp311-manylinux_2_17_aarch64."
    "manylinux2014_aarch64.whl"
)
YARA_SHA256 = "a0176641510cff158ab56fd60f8d3b67ffd804441def44df53a87a0632090225"
YARA_URL = "https://pypi.org/project/yara-python-dex/1.0.7/"


def find_wheel(wheelhouse: Path, filename: str) -> Path:
    path = wheelhouse / filename
    if not path.is_file():
        raise SystemExit(f"missing pinned wheel: {path}")
    return path


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Build the complete APKiD AutoCrack Toolpack"
    )
    parser.add_argument("--wheelhouse", required=True, type=Path)
    parser.add_argument("--output-dir", type=Path, default=ROOT / "dist")
    args = parser.parse_args()

    apkid_wheel = find_wheel(args.wheelhouse, APKID_WHEEL)
    yara_wheel = find_wheel(args.wheelhouse, YARA_WHEEL)
    require_sha256(apkid_wheel, APKID_SHA256)
    require_sha256(yara_wheel, YARA_SHA256)

    with tempfile.TemporaryDirectory() as temporary_directory:
        payload = Path(temporary_directory) / "payload"
        python_root = payload / "python"
        install_wheels([apkid_wheel, yara_wheel], python_root)
        copy_executable(ROOT / "bin" / "apkid", payload / "bin" / "apkid")
        shutil.copy2(ROOT / "SKILL.md", payload / "SKILL.md")

        required = [
            python_root / "apkid" / "main.py",
            python_root / "apkid" / "rules" / "rules.yarc",
        ]
        for path in required:
            if not path.is_file():
                raise SystemExit(
                    f"wheel payload missing required upstream file: {path}"
                )
        if not any(
            path.name.startswith("yara") and path.suffix == ".so"
            for path in python_root.iterdir()
        ):
            raise SystemExit(
                "yara-python-dex ARM64 extension was not found in wheel payload"
            )

        manifest = {
            "schemaVersion": 2,
            "id": TOOLPACK_ID,
            "title": "APKiD full Android packer and protection identification",
            "version": VERSION,
            "description": (
                "Complete upstream APKiD 3.1.0 CLI/Python API/rule corpus with "
                "the pinned Linux ARM64 yara-python-dex backend. AutoCrack adds "
                "no capability-reducing wrapper."
            ),
            "architecture": "arm64",
            "payloadEntry": "payload.zip",
            "payloadSha256": "0" * 64,
            "payloadSizeBytes": 1,
            "requiredPaths": [
                "bin/apkid",
                "python/apkid/main.py",
                "python/apkid/rules/rules.yarc",
                "SKILL.md",
            ],
            "commands": [
                {
                    "name": "apkid",
                    "relativePath": "bin/apkid",
                    "description": (
                        "Complete upstream APKiD CLI with text and JSON output."
                    ),
                },
            ],
            "selfTests": [
                {
                    "id": "apkid-version",
                    "title": "Upstream APKiD CLI",
                    "command": "apkid --help",
                    "expectedExitCodes": [0],
                    "outputContains": [
                        f"APKiD - Android Application Identifier v{APKID_VERSION}"
                    ],
                },
                {
                    "id": "apkid-python-api",
                    "title": "Complete APKiD Python API and rules",
                    "command": (
                        "PYTHONDONTWRITEBYTECODE=1 python3 -B -c "
                        "\"import apkid,apkid.apkid,apkid.rules,yara;"
                        "print('AUTOCRACK_APKID_API_OK')\""
                    ),
                    "expectedExitCodes": [0],
                    "outputContains": ["AUTOCRACK_APKID_API_OK"],
                },
            ],
            "sources": [
                {
                    "name": "apkid-wheel",
                    "version": APKID_VERSION,
                    "url": APKID_URL,
                    "sha256": APKID_SHA256,
                },
                {
                    "name": "yara-python-dex-linux-aarch64",
                    "version": YARA_DEX_VERSION,
                    "url": YARA_URL,
                    "sha256": YARA_SHA256,
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
            executables={"bin/apkid"},
        )
        print(f"VERSION={VERSION}")
        print(f"OUTPUT={output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
