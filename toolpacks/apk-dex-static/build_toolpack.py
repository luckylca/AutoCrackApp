#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import stat
import tempfile
import zipfile
from pathlib import Path

FIXED_TIME = (1980, 1, 1, 0, 0, 0)
TOOLPACK_ID = "apk-dex-static"
TOOLPACK_VERSION = "jadx-1.5.6_apktool-3.0.3_autocrack-1.0.1"
OUTPUT_NAME = f"AutoCrackApp-{TOOLPACK_ID}-{TOOLPACK_VERSION}-toolpack.zip"


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def copy_executable(src: Path, dst: Path) -> None:
    dst.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src, dst)
    dst.chmod(dst.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)


def extract_jadx(jadx_zip: Path, target: Path) -> None:
    with zipfile.ZipFile(jadx_zip) as archive:
        archive.extractall(target)
    children = [path for path in target.iterdir() if path.is_dir()]
    if len(children) == 1 and (children[0] / "bin" / "jadx").exists():
        inner = children[0]
        temporary = target.with_name(target.name + ".inner")
        inner.rename(temporary)
        shutil.rmtree(target)
        temporary.rename(target)
    if not (target / "bin" / "jadx").exists():
        raise SystemExit("JADX zip did not contain bin/jadx")


def deterministic_zip(root: Path, output: Path) -> None:
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED) as archive:
        for path in sorted(item for item in root.rglob("*") if item.is_file()):
            relative = path.relative_to(root).as_posix()
            info = zipfile.ZipInfo(relative, FIXED_TIME)
            info.create_system = 3
            info.compress_type = zipfile.ZIP_STORED
            mode = 0o755 if relative in {"bin/jadx", "bin/apktool"} else 0o644
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


def main() -> int:
    parser = argparse.ArgumentParser(description="Build AutoCrack apk-dex-static toolpack")
    parser.add_argument("--jadx-zip", required=True, type=Path)
    parser.add_argument("--apktool-jar", required=True, type=Path)
    parser.add_argument("--output-dir", type=Path)
    args = parser.parse_args()
    if not args.jadx_zip.is_file():
        raise SystemExit("--jadx-zip missing")
    if not args.apktool_jar.is_file():
        raise SystemExit("--apktool-jar missing")

    root = Path(__file__).resolve().parent
    output_dir = args.output_dir or (root / "dist")
    if output_dir.exists():
        shutil.rmtree(output_dir)
    output_dir.mkdir(parents=True)

    with tempfile.TemporaryDirectory() as temporary_directory:
        payload = Path(temporary_directory) / "payload"
        payload.mkdir()
        extract_jadx(args.jadx_zip, payload / "lib" / "jadx")
        (payload / "lib" / "apktool").mkdir(parents=True)
        shutil.copy2(args.apktool_jar, payload / "lib" / "apktool" / "apktool.jar")
        copy_executable(root / "bin" / "jadx", payload / "bin" / "jadx")
        copy_executable(root / "bin" / "apktool", payload / "bin" / "apktool")
        shutil.copy2(root / "SKILL.md", payload / "SKILL.md")
        payload_zip = output_dir / "payload.zip"
        deterministic_zip(payload, payload_zip)

    payload_hash = sha256(payload_zip)
    payload_size = payload_zip.stat().st_size
    manifest_data = {
        "schemaVersion": 1,
        "id": TOOLPACK_ID,
        "title": "JADX and Apktool static APK/DEX analysis",
        "version": TOOLPACK_VERSION,
        "description": (
            "Static APK/DEX analysis for the Debian rootfs. JADX uses a mobile-safe default budget "
            "of 768 MB heap and 2 worker CPUs; prefer targeted --single-class analysis for large APKs."
        ),
        "architecture": "all",
        "payloadEntry": "payload.zip",
        "payloadSha256": payload_hash,
        "payloadSizeBytes": payload_size,
        "requiredPaths": [
            "bin/jadx",
            "bin/apktool",
            "lib/jadx/bin/jadx",
            "lib/apktool/apktool.jar",
            "SKILL.md",
        ],
        "commands": [
            {
                "name": "jadx",
                "relativePath": "bin/jadx",
                "description": (
                    "JADX CLI with mobile-safe defaults (768 MB heap, 2 threads). For large APKs first narrow "
                    "the target and prefer --single-class; use jadx --autocrack-policy to inspect the active budget."
                ),
            },
            {
                "name": "apktool",
                "relativePath": "bin/apktool",
                "description": "Decode Android resources and smali without full Java decompilation.",
            },
        ],
        "selfTests": [
            {
                "id": "java-version",
                "title": "Java runtime",
                "command": "java -version",
                "expectedExitCodes": [0],
                "outputContains": ["version"],
            },
            {
                "id": "jadx-version",
                "title": "JADX CLI",
                "command": "jadx --version",
                "expectedExitCodes": [0],
                "outputContains": ["1.5.6"],
            },
            {
                "id": "jadx-mobile-policy",
                "title": "JADX mobile resource policy",
                "command": "jadx --autocrack-policy",
                "expectedExitCodes": [0],
                "outputContains": ["AUTOC_JADX_HEAP_MB=768", "AUTOC_JADX_THREADS=2"],
            },
            {
                "id": "apktool-version",
                "title": "Apktool",
                "command": "apktool --version",
                "expectedExitCodes": [0],
                "outputContains": ["3.0.3"],
            },
        ],
        "sources": [
            {
                "name": "jadx",
                "version": "1.5.6",
                "url": "https://github.com/skylot/jadx/releases/download/v1.5.6/jadx-1.5.6.zip",
                "sha256": sha256(args.jadx_zip),
            },
            {
                "name": "apktool",
                "version": "3.0.3",
                "url": "https://github.com/iBotPeaches/Apktool/releases/download/v3.0.3/apktool_3.0.3.jar",
                "sha256": sha256(args.apktool_jar),
            },
        ],
    }
    manifest = output_dir / "manifest.json"
    manifest.write_text(json.dumps(manifest_data, indent=2) + "\n", encoding="utf-8")
    output = output_dir / OUTPUT_NAME
    write_outer(output, manifest, payload_zip)

    print(f"TOOLPACK={output}")
    print(f"PAYLOAD_SHA256={payload_hash}")
    print(f"PAYLOAD_SIZE={payload_size}")
    print(f"TOOLPACK_SHA256={sha256(output)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
