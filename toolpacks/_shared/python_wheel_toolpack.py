#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import json
import shutil
import stat
import tempfile
import zipfile
from pathlib import Path

FIXED_TIME = (1980, 1, 1, 0, 0, 0)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def require_sha256(path: Path, expected: str) -> None:
    actual = sha256(path)
    if actual != expected.lower():
        raise SystemExit(
            f"SHA-256 mismatch for {path.name}: expected={expected} actual={actual}"
        )


def extract_wheel(wheel: Path, target: Path) -> None:
    if wheel.suffix != ".whl":
        raise SystemExit(f"not a wheel: {wheel}")
    with zipfile.ZipFile(wheel) as archive:
        for info in sorted(archive.infolist(), key=lambda item: item.filename):
            name = info.filename
            if not name or name.endswith("/"):
                continue
            parts = Path(name).parts
            if name.startswith("/") or ".." in parts:
                raise SystemExit(f"unsafe wheel member: {name}")
            destination = target / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(info) as source, destination.open("wb") as output:
                shutil.copyfileobj(source, output)


def install_wheels(wheels: list[Path], target: Path) -> None:
    target.mkdir(parents=True, exist_ok=True)
    for wheel in sorted(wheels, key=lambda item: item.name):
        extract_wheel(wheel, target)

def load_locked_wheelhouse(lock_path: Path, wheelhouse: Path) -> tuple[list[Path], list[dict]]:
    data = json.loads(lock_path.read_text(encoding="utf-8"))
    entries = data.get("wheels")
    if not isinstance(entries, list) or not entries:
        raise SystemExit(f"invalid or empty wheel lock: {lock_path}")
    expected_names = {entry["filename"] for entry in entries}
    actual_names = {path.name for path in wheelhouse.glob("*.whl")}
    if actual_names != expected_names:
        missing = sorted(expected_names - actual_names)
        unexpected = sorted(actual_names - expected_names)
        raise SystemExit(
            f"wheelhouse does not match lock: missing={missing} unexpected={unexpected}"
        )
    wheels = []
    for entry in entries:
        path = wheelhouse / entry["filename"]
        require_sha256(path, entry["sha256"])
        wheels.append(path)
    return wheels, entries



def copy_executable(source: Path, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, destination)
    destination.chmod(0o755)


def deterministic_zip(root: Path, output: Path, executables: set[str]) -> None:
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED) as archive:
        for path in sorted(item for item in root.rglob("*") if item.is_file()):
            relative = path.relative_to(root).as_posix()
            info = zipfile.ZipInfo(relative, FIXED_TIME)
            info.create_system = 3
            info.compress_type = zipfile.ZIP_STORED
            mode = (
                0o755
                if relative in executables or relative.startswith("bin/")
                else 0o644
            )
            info.external_attr = (stat.S_IFREG | mode) << 16
            archive.writestr(info, path.read_bytes())


def write_toolpack(
    *,
    payload_root: Path,
    manifest: dict,
    output_dir: Path,
    filename: str,
    executables: set[str],
) -> Path:
    output_dir.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as temporary_directory:
        temporary = Path(temporary_directory)
        payload_zip = temporary / "payload.zip"
        deterministic_zip(payload_root, payload_zip, executables)
        manifest["payloadSha256"] = sha256(payload_zip)
        manifest["payloadSizeBytes"] = payload_zip.stat().st_size
        manifest_path = temporary / "manifest.json"
        manifest_path.write_text(
            json.dumps(manifest, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        output = output_dir / filename
        with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED) as archive:
            for item in (manifest_path, payload_zip):
                info = zipfile.ZipInfo(item.name, FIXED_TIME)
                info.create_system = 3
                info.compress_type = zipfile.ZIP_STORED
                info.external_attr = (stat.S_IFREG | 0o644) << 16
                archive.writestr(info, item.read_bytes())
        shutil.copy2(manifest_path, output_dir / "manifest.json")
        shutil.copy2(payload_zip, output_dir / "payload.zip")
        (output_dir / "payload.sha256").write_text(
            manifest["payloadSha256"] + "\n",
            encoding="utf-8",
        )
        (output_dir / "payload.size").write_text(
            str(manifest["payloadSizeBytes"]) + "\n",
            encoding="utf-8",
        )
        (output_dir / f"{output.name}.sha256").write_text(
            f"{sha256(output)}  {output.name}\n",
            encoding="utf-8",
        )
        print(f"TOOLPACK={output}")
        print(f"PAYLOAD_SHA256={manifest['payloadSha256']}")
        print(f"PAYLOAD_SIZE={manifest['payloadSizeBytes']}")
        print(f"TOOLPACK_SHA256={sha256(output)}")
        return output
