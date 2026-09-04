#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
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
    install_wheels,
    load_locked_wheelhouse,
    require_sha256,
    sha256,
    write_toolpack,
)

TOOLPACK_ID = "capa"
CAPA_VERSION = "9.4.0"
RULES_VERSION = "9.4.0"
RULES_COMMIT = "2af9fbfc1c9b4634dbeb76b5d34fca9389fa7f80"
VERSION = "capa-9.4.0_rules-9.4.0_autocrack-1.0.0"

CAPA_SDIST = "flare_capa-9.4.0.tar.gz"
CAPA_SDIST_URL = (
    "https://files.pythonhosted.org/packages/source/f/flare-capa/"
    "flare_capa-9.4.0.tar.gz"
)
CAPA_SDIST_SHA256 = "c4f421abac566e23657241e4ddc66119beb0caf5f082ee42d68b7c879ebb7fc6"

RULES_ZIP = f"capa-rules-{RULES_COMMIT}.zip"
RULES_URL = f"https://codeload.github.com/mandiant/capa-rules/zip/{RULES_COMMIT}"
RULES_SHA256 = "2b3408c0ef9313683cfe2b7dab6c3fb8c2ac3fa8bb0c95281341d220dfc5e1ca"

EXPECTED_RULE_YAML_COUNT = 1042
EXPECTED_SIGNATURE_COUNT = 3


def safe_extract_signatures(sdist: Path, target: Path) -> None:
    expected_root = f"flare_capa-{CAPA_VERSION}"
    target.mkdir(parents=True, exist_ok=True)
    copied = 0
    with tarfile.open(sdist, "r:gz") as source:
        for member in source.getmembers():
            parts = Path(member.name).parts
            if member.name.startswith("/") or ".." in parts:
                raise SystemExit(f"unsafe capa sdist member: {member.name}")
            if len(parts) < 3 or parts[0] != expected_root or parts[1] != "sigs":
                continue
            relative = Path(*parts[2:])
            if member.isdir():
                (target / relative).mkdir(parents=True, exist_ok=True)
                continue
            if not member.isfile():
                raise SystemExit(f"unexpected capa signature member type: {member.name}")
            destination = target / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            extracted = source.extractfile(member)
            if extracted is None:
                raise SystemExit(f"unable to extract capa signature member: {member.name}")
            with extracted, destination.open("wb") as output:
                shutil.copyfileobj(extracted, output)
            copied += 1

    signatures = sorted(target.glob("*.sig"))
    if len(signatures) != EXPECTED_SIGNATURE_COUNT:
        raise SystemExit(
            f"unexpected capa signature count: expected={EXPECTED_SIGNATURE_COUNT} "
            f"actual={len(signatures)} copied_files={copied}"
        )


def safe_extract_rules(archive: Path, target: Path) -> None:
    expected_root = f"capa-rules-{RULES_COMMIT}"
    target.mkdir(parents=True, exist_ok=True)

    with zipfile.ZipFile(archive) as source:
        for info in sorted(source.infolist(), key=lambda item: item.filename):
            name = info.filename
            parts = Path(name).parts
            if name.startswith("/") or ".." in parts:
                raise SystemExit(f"unsafe capa-rules member: {name}")
            if not parts or parts[0] != expected_root:
                raise SystemExit(f"unexpected capa-rules archive root: {name}")
            if len(parts) == 1:
                continue

            relative = Path(*parts[1:])
            # GitHub repository automation files are not capa rules. In
            # particular, three workflow .yml files would otherwise be parsed
            # recursively by capa.rules.get_rules().
            if relative.parts[0] == ".github":
                continue

            destination = target / relative
            if info.is_dir():
                destination.mkdir(parents=True, exist_ok=True)
                continue
            destination.parent.mkdir(parents=True, exist_ok=True)
            with source.open(info) as input_handle, destination.open("wb") as output:
                shutil.copyfileobj(input_handle, output)

    rules = sorted(target.rglob("*.yml"))
    if len(rules) != EXPECTED_RULE_YAML_COUNT:
        raise SystemExit(
            f"unexpected capa rule count: expected={EXPECTED_RULE_YAML_COUNT} "
            f"actual={len(rules)}"
        )
    if not (target / "nursery").is_dir():
        raise SystemExit("capa nursery rules were not preserved")


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Build complete capa 9.4.0 ARM64 Python/API/rules Toolpack"
    )
    parser.add_argument("--wheelhouse", required=True, type=Path)
    parser.add_argument(
        "--lock",
        type=Path,
        default=ROOT / "wheelhouse.lock.json",
    )
    parser.add_argument("--capa-sdist", required=True, type=Path)
    parser.add_argument("--rules-zip", required=True, type=Path)
    parser.add_argument("--output-dir", type=Path, default=ROOT / "dist")
    args = parser.parse_args()

    if args.capa_sdist.name != CAPA_SDIST:
        raise SystemExit(f"unexpected capa sdist filename: {args.capa_sdist.name}")
    if args.rules_zip.name != RULES_ZIP:
        raise SystemExit(f"unexpected capa rules filename: {args.rules_zip.name}")

    require_sha256(args.capa_sdist, CAPA_SDIST_SHA256)
    require_sha256(args.rules_zip, RULES_SHA256)

    lock = json.loads(args.lock.read_text(encoding="utf-8"))
    if lock.get("python") != "3.11":
        raise SystemExit("capa wheel lock must target Python 3.11")
    if lock.get("platform") != "manylinux2014_aarch64":
        raise SystemExit("capa wheel lock must target manylinux2014_aarch64")

    wheels, source_entries = load_locked_wheelhouse(args.lock, args.wheelhouse)
    flare_entries = [
        entry
        for entry in source_entries
        if entry["name"].replace("_", "-").lower() == "flare-capa"
        and entry["version"] == CAPA_VERSION
    ]
    if len(flare_entries) != 1:
        raise SystemExit("wheelhouse must contain exactly flare-capa 9.4.0")

    with tempfile.TemporaryDirectory() as temporary_directory:
        temporary = Path(temporary_directory)
        payload = temporary / "payload"
        python_root = payload / "python"
        install_wheels(wheels, python_root)

        safe_extract_rules(args.rules_zip, python_root / "rules")
        safe_extract_signatures(args.capa_sdist, python_root / "sigs")

        copy_executable(ROOT / "bin" / "capa", payload / "bin" / "capa")
        shutil.copy2(ROOT / "SKILL.md", payload / "SKILL.md")
        shutil.copy2(ROOT / "VERSION", payload / "VERSION")
        shutil.copy2(args.lock, payload / "WHEELHOUSE.lock.json")

        required_files = (
            python_root / "capa" / "__init__.py",
            python_root / "capa" / "main.py",
            python_root / "capa" / "loader.py",
            python_root / "capa" / "engine.py",
            python_root / "capa" / "rules" / "__init__.py",
            python_root / "capa" / "render" / "json.py",
            python_root
            / "rules"
            / "anti-analysis"
            / "anti-av"
            / "block-operations-on-executable-memory-pages-using-arbitrary-code-guard.yml",
            python_root / "sigs" / "1_flare_msvc_rtf_32_64.sig",
        )
        for required in required_files:
            if not required.is_file():
                raise SystemExit(f"missing complete capa runtime/resource file: {required}")

        sources = [
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
                "name": "flare-capa-sdist-signatures",
                "version": CAPA_VERSION,
                "url": CAPA_SDIST_URL,
                "sha256": CAPA_SDIST_SHA256,
            },
            {
                "name": "capa-rules-source",
                "version": RULES_COMMIT,
                "url": RULES_URL,
                "sha256": RULES_SHA256,
            },
            {
                "name": "wheelhouse-lock",
                "version": VERSION,
                "url": (
                    "https://github.com/luckylca/AutoCrackApp/blob/main/"
                    "toolpacks/capa/wheelhouse.lock.json"
                ),
                "sha256": sha256(args.lock),
            },
        ]

        manifest = {
            "schemaVersion": 2,
            "id": TOOLPACK_ID,
            "title": "capa complete ARM64 capability analysis API and rules",
            "version": VERSION,
            "description": (
                "Complete flare-capa 9.4.0 upstream CLI/Python API on Linux ARM64, "
                "with 1042 matching capa-rules v9.4.0 rule YAMLs (including nursery) "
                "and the three official embedded FLIRT signatures."
            ),
            "architecture": "arm64",
            "payloadEntry": "payload.zip",
            "payloadSha256": "0" * 64,
            "payloadSizeBytes": 1,
            "requiredPaths": [
                "bin/capa",
                "python/capa/__init__.py",
                "python/capa/main.py",
                "python/capa/loader.py",
                "python/capa/engine.py",
                "python/capa/rules/__init__.py",
                "python/capa/render/json.py",
                "python/rules/anti-analysis/anti-av/block-operations-on-executable-memory-pages-using-arbitrary-code-guard.yml",
                "python/rules/nursery",
                "python/sigs/1_flare_msvc_rtf_32_64.sig",
                "python/sigs/2_flare_msvc_atlmfc_32_64.sig",
                "python/sigs/3_flare_common_libs.sig",
                "WHEELHOUSE.lock.json",
                "SKILL.md",
                "VERSION",
            ],
            "commands": [
                {
                    "name": "capa",
                    "relativePath": "bin/capa",
                    "description": "Complete upstream capa 9.4.0 CLI using embedded default rules/signatures.",
                },
            ],
            "selfTests": [
                {
                    "id": "capa-version",
                    "title": "Upstream capa CLI version",
                    "command": "capa --version",
                    "expectedExitCodes": [0],
                    "outputContains": ["capa 9.4.0"],
                },
                {
                    "id": "capa-cli",
                    "title": "Complete upstream capa CLI",
                    "command": "capa --help",
                    "expectedExitCodes": [0],
                    "outputContains": ["--json", "--rules", "--signatures"],
                },
                {
                    "id": "capa-python-api",
                    "title": "Full Python API and embedded rule/signature resources",
                    "command": (
                        "PYTHONDONTWRITEBYTECODE=1 python3 -B -c "
                        "\"import pathlib,capa,capa.main,capa.loader,capa.rules,capa.engine,capa.render.json;"
                        "root=pathlib.Path(capa.__file__).resolve().parent.parent;"
                        "assert len(list((root/'rules').rglob('*.yml')))==1042;"
                        "assert len(list((root/'sigs').glob('*.sig')))==3;"
                        "capa.rules.get_rules([root/'rules']);"
                        "assert len(capa.main.get_default_signatures())==3;"
                        "print('AUTOCRACK_CAPA_API_RULES_OK')\""
                    ),
                    "expectedExitCodes": [0],
                    "outputContains": ["AUTOCRACK_CAPA_API_RULES_OK"],
                },
            ],
            "sources": sources,
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
            executables={"bin/capa"},
        )
        print(f"DEPENDENCY_WHEELS={len(wheels)}")
        print(f"RULE_YAMLS={EXPECTED_RULE_YAML_COUNT}")
        print(f"SIGNATURES={EXPECTED_SIGNATURE_COUNT}")
        print(f"VERSION={VERSION}")
        print(f"OUTPUT={output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
