#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import shutil
import sys
import tarfile
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SHARED = ROOT.parent / "_shared"
sys.path.insert(0, str(SHARED))

from python_wheel_toolpack import (  # noqa: E402
    copy_executable,
    extract_wheel,
    install_wheels,
    load_locked_wheelhouse,
    require_sha256,
    sha256,
    write_toolpack,
)

TOOLPACK_ID = "uiautomator2"
U2_VERSION = "3.7.0"
ADBUTILS_VERSION = "2.11.0"
VERSION = "uiautomator2-3.7.0_adbutils-2.11.0_autocrack-1.0.0"

U2_WHEEL = "uiautomator2-3.7.0-py3-none-any.whl"
U2_SHA256 = "731bf4e26e35cd440cd165b399b8a4d4b795178d78b9243769e336aee6dce985"
U2_URL = "https://files.pythonhosted.org/packages/55/23/a5f93de8bb197ae2d2d0185c2c13d4b36ae7f18215e3e599e217f8e90e0d/uiautomator2-3.7.0-py3-none-any.whl"

ADBUTILS_SDIST = "adbutils-2.11.0.tar.gz"
ADBUTILS_SHA256 = "7621182b219163bbfd16a240aa504c7834cf377d2bdc3f10452d5e8266fa7f87"
ADBUTILS_URL = "https://files.pythonhosted.org/packages/b6/80/c0cc9b47c8273f1f9c5f00577605f9c5903acf74cd80569fa1cb1072fc9f/adbutils-2.11.0.tar.gz"


def safe_extract_adbutils(archive: Path, target: Path) -> Path:
    target.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive, "r:gz") as source:
        members = source.getmembers()
        for member in members:
            parts = Path(member.name).parts
            if member.name.startswith("/") or ".." in parts:
                raise SystemExit(f"unsafe adbutils tar member: {member.name}")
        source.extractall(target)
    root = target / f"adbutils-{ADBUTILS_VERSION}"
    if not root.is_dir():
        raise SystemExit(f"adbutils sdist missing expected root: {root}")
    return root


def install_adbutils(source_root: Path, python_root: Path, upstream_root: Path) -> None:
    package = source_root / "adbutils"
    egg_info = source_root / "adbutils.egg-info"
    if not package.is_dir() or not egg_info.is_dir():
        raise SystemExit("adbutils sdist missing package or egg-info")
    shutil.copytree(package, python_root / "adbutils")
    shutil.copytree(egg_info, python_root / "adbutils.egg-info")
    upstream_root.mkdir(parents=True, exist_ok=True)
    for name in ("LICENSE", "README.md", "ChangeLog"):
        source = source_root / name
        if source.is_file():
            shutil.copy2(source, upstream_root / name)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Build complete upstream uiautomator2 AutoCrack Toolpack"
    )
    parser.add_argument("--uiautomator2-wheel", required=True, type=Path)
    parser.add_argument("--adbutils-sdist", required=True, type=Path)
    parser.add_argument("--wheelhouse", required=True, type=Path)
    parser.add_argument(
        "--lock",
        type=Path,
        default=ROOT / "wheelhouse.lock.json",
    )
    parser.add_argument("--output-dir", type=Path, default=ROOT / "dist")
    args = parser.parse_args()

    if args.uiautomator2_wheel.name != U2_WHEEL:
        raise SystemExit(f"unexpected uiautomator2 wheel: {args.uiautomator2_wheel.name}")
    if args.adbutils_sdist.name != ADBUTILS_SDIST:
        raise SystemExit(f"unexpected adbutils sdist: {args.adbutils_sdist.name}")
    require_sha256(args.uiautomator2_wheel, U2_SHA256)
    require_sha256(args.adbutils_sdist, ADBUTILS_SHA256)

    lock = json.loads(args.lock.read_text(encoding="utf-8"))
    if lock.get("python") != "3.11":
        raise SystemExit("uiautomator2 dependency lock must target Python 3.11")
    if lock.get("platform") != "manylinux2014_aarch64":
        raise SystemExit("uiautomator2 dependency lock must target manylinux2014_aarch64")
    wheels, source_entries = load_locked_wheelhouse(args.lock, args.wheelhouse)

    with tempfile.TemporaryDirectory() as temporary_directory:
        temporary = Path(temporary_directory)
        payload = temporary / "payload"
        python_root = payload / "python"
        python_root.mkdir(parents=True)

        install_wheels(wheels, python_root)
        extract_wheel(args.uiautomator2_wheel, python_root)
        adbutils_root = safe_extract_adbutils(
            args.adbutils_sdist,
            temporary / "adbutils-source",
        )
        install_adbutils(
            adbutils_root,
            python_root,
            payload / "upstream" / "adbutils",
        )

        copy_executable(ROOT / "bin" / "uiautomator2", payload / "bin" / "uiautomator2")
        copy_executable(ROOT / "bin" / "u2cli", payload / "bin" / "u2cli")
        shutil.copy2(ROOT / "SKILL.md", payload / "SKILL.md")
        shutil.copy2(ROOT / "VERSION", payload / "VERSION")
        shutil.copy2(args.lock, payload / "WHEELHOUSE.lock.json")

        required_files = (
            python_root / "uiautomator2" / "__init__.py",
            python_root / "uiautomator2" / "__main__.py",
            python_root / "uiautomator2" / "agent_cli" / "__main__.py",
            python_root / "uiautomator2" / "assets" / "app-uiautomator.apk",
            python_root / "uiautomator2" / "assets" / "u2.jar",
            python_root / "uiautomator2" / "assets" / "version.json",
            python_root / "adbutils" / "__init__.py",
            python_root / "adbutils" / "_utils.py",
            python_root / "adbutils.egg-info" / "PKG-INFO",
        )
        for required in required_files:
            if not required.is_file():
                raise SystemExit(f"missing upstream uiautomator2 runtime file: {required}")

        sources = [
            {
                "name": "uiautomator2-wheel",
                "version": U2_VERSION,
                "url": U2_URL,
                "sha256": U2_SHA256,
            },
            {
                "name": "adbutils-sdist",
                "version": ADBUTILS_VERSION,
                "url": ADBUTILS_URL,
                "sha256": ADBUTILS_SHA256,
            },
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
                    "toolpacks/uiautomator2/wheelhouse.lock.json"
                ),
                "sha256": sha256(args.lock),
            },
        ]

        manifest = {
            "schemaVersion": 2,
            "id": TOOLPACK_ID,
            "title": "uiautomator2 complete Android UI automation",
            "version": VERSION,
            "description": (
                "Complete upstream uiautomator2 3.7.0 Python API, uiautomator2/u2cli "
                "entrypoints and embedded device assets, with upstream adbutils 2.11.0 "
                "sdist and pinned Linux ARM64 Python dependencies."
            ),
            "architecture": "arm64",
            "payloadEntry": "payload.zip",
            "payloadSha256": "0" * 64,
            "payloadSizeBytes": 1,
            "requiredPaths": [
                "bin/uiautomator2",
                "bin/u2cli",
                "python/uiautomator2/__init__.py",
                "python/uiautomator2/__main__.py",
                "python/uiautomator2/agent_cli/__main__.py",
                "python/uiautomator2/assets/app-uiautomator.apk",
                "python/uiautomator2/assets/u2.jar",
                "python/uiautomator2/assets/version.json",
                "python/adbutils/__init__.py",
                "python/adbutils/_utils.py",
                "python/adbutils.egg-info/PKG-INFO",
                "WHEELHOUSE.lock.json",
                "SKILL.md",
                "VERSION",
            ],
            "commands": [
                {
                    "name": "uiautomator2",
                    "relativePath": "bin/uiautomator2",
                    "description": "Complete upstream uiautomator2 argparse CLI.",
                },
                {
                    "name": "u2cli",
                    "relativePath": "bin/u2cli",
                    "description": "Complete upstream u2cli Click command group.",
                },
            ],
            "selfTests": [
                {
                    "id": "uiautomator2-version",
                    "title": "Upstream uiautomator2 CLI",
                    "command": "uiautomator2 version",
                    "expectedExitCodes": [0],
                    "outputContains": ["uiautomator2 version: 3.7.0"],
                },
                {
                    "id": "u2cli-help",
                    "title": "Upstream u2cli command group",
                    "command": "u2cli --help",
                    "expectedExitCodes": [0],
                    "outputContains": ["Usage:", "u2cli"],
                },
                {
                    "id": "uiautomator2-python-api",
                    "title": "Full Python API, adbutils and embedded device assets",
                    "command": (
                        "PYTHONDONTWRITEBYTECODE=1 python3 -B -c "
                        "\"import importlib.metadata,pathlib,shutil,uiautomator2,adbutils;"
                        "p=pathlib.Path(uiautomator2.__file__).parent/'assets';"
                        "assert (p/'app-uiautomator.apk').is_file();"
                        "assert (p/'u2.jar').is_file();"
                        "assert shutil.which('adb');"
                        "print(importlib.metadata.version('uiautomator2'));"
                        "print(importlib.metadata.version('adbutils'));"
                        "print('AUTOCRACK_UIAUTOMATOR2_API_OK')\""
                    ),
                    "expectedExitCodes": [0],
                    "outputContains": [
                        U2_VERSION,
                        ADBUTILS_VERSION,
                        "AUTOCRACK_UIAUTOMATOR2_API_OK",
                    ],
                },
            ],
            "sources": sources,
            "requires": {
                "runtime": None,
                "capabilities": [],
                "commands": ["adb"],
                "optionalCapabilities": [],
            },
        }

        output = write_toolpack(
            payload_root=payload,
            manifest=manifest,
            output_dir=args.output_dir,
            filename=f"AutoCrackApp-{TOOLPACK_ID}-{VERSION}-toolpack.zip",
            executables={"bin/uiautomator2", "bin/u2cli"},
        )
        print(f"DEPENDENCY_WHEELS={len(wheels)}")
        print(f"VERSION={VERSION}")
        print(f"OUTPUT={output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
