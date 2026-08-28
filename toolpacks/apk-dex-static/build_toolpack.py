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

TOOLPACK_ID = "apk-dex-static"
TOOLPACK_VERSION = "jadx-1.5.6_apktool-3.0.3"


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
    with zipfile.ZipFile(jadx_zip) as zf:
        zf.extractall(target)
    children = [p for p in target.iterdir() if p.is_dir()]
    if len(children) == 1 and (children[0] / "bin" / "jadx").exists():
        inner = children[0]
        tmp = target.with_name(target.name + ".inner")
        inner.rename(tmp)
        shutil.rmtree(target)
        tmp.rename(target)
    if not (target / "bin" / "jadx").exists():
        raise SystemExit("JADX zip did not contain bin/jadx")


def write_package(payload_dir: Path, output_dir: Path, jadx_zip: Path, apktool_jar: Path) -> Path:
    payload_zip = output_dir / f"{TOOLPACK_ID}-{TOOLPACK_VERSION}-payload.zip"
    with zipfile.ZipFile(payload_zip, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for path in sorted(payload_dir.rglob("*")):
            if path.is_file():
                zf.write(path, path.relative_to(payload_dir).as_posix())
    manifest = {
        "schemaVersion": 1,
        "id": TOOLPACK_ID,
        "title": "JADX and Apktool static APK/DEX analysis",
        "version": TOOLPACK_VERSION,
        "payloadSha256": sha256(payload_zip),
        "sources": [
            {"name": "jadx", "version": "1.5.6", "sha256": sha256(jadx_zip)},
            {"name": "apktool", "version": "3.0.3", "sha256": sha256(apktool_jar)},
        ],
        "commands": [
            {"name": "jadx", "path": "bin/jadx"},
            {"name": "apktool", "path": "bin/apktool"},
        ],
        "selfTests": [
            {"id": "jadx-version", "title": "JADX prints a version", "command": "jadx --version", "expectedOutputContains": "1.5.6"},
            {"id": "apktool-version", "title": "Apktool prints a version", "command": "apktool --version", "expectedOutputContains": "3.0.3"},
        ],
    }
    manifest_path = output_dir / f"{TOOLPACK_ID}-{TOOLPACK_VERSION}-manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return payload_zip


def main() -> int:
    parser = argparse.ArgumentParser(description="Build AutoCrack apk-dex-static toolpack")
    parser.add_argument("--jadx-zip", required=True, type=Path)
    parser.add_argument("--apktool-jar", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    args = parser.parse_args()
    if not args.jadx_zip.is_file():
        raise SystemExit("--jadx-zip missing")
    if not args.apktool_jar.is_file():
        raise SystemExit("--apktool-jar missing")
    args.output_dir.mkdir(parents=True, exist_ok=True)
    root = Path(__file__).resolve().parent
    with tempfile.TemporaryDirectory() as td:
        payload = Path(td) / "payload"
        payload.mkdir()
        extract_jadx(args.jadx_zip, payload / "jadx")
        (payload / "lib").mkdir()
        shutil.copy2(args.apktool_jar, payload / "lib" / "apktool.jar")
        copy_executable(root / "bin" / "jadx", payload / "bin" / "jadx")
        copy_executable(root / "bin" / "apktool", payload / "bin" / "apktool")
        payload_zip = write_package(payload, args.output_dir, args.jadx_zip, args.apktool_jar)
    print(f"APK_DEX_TOOLPACK_VERSION={TOOLPACK_VERSION}")
    print(f"APK_DEX_TOOLPACK_PAYLOAD={payload_zip}")
    print(f"APK_DEX_TOOLPACK_SHA256={sha256(payload_zip)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
