#!/usr/bin/env python3
from __future__ import annotations

import argparse
import email
import hashlib
import json
import zipfile
from pathlib import Path


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def wheel_metadata(path: Path) -> tuple[str, str]:
    with zipfile.ZipFile(path) as archive:
        metadata_names = [
            name for name in archive.namelist() if name.endswith(".dist-info/METADATA")
        ]
        if len(metadata_names) != 1:
            raise SystemExit(f"expected one METADATA in {path.name}")
        message = email.message_from_bytes(archive.read(metadata_names[0]))
        name = message.get("Name")
        version = message.get("Version")
        if not name or not version:
            raise SystemExit(f"wheel metadata missing Name/Version: {path.name}")
        return name, version


def snapshot(wheelhouse: Path) -> dict:
    entries = []
    for wheel in sorted(wheelhouse.glob("*.whl"), key=lambda path: path.name.lower()):
        name, version = wheel_metadata(wheel)
        entries.append(
            {
                "name": name,
                "version": version,
                "file": wheel.name,
                "sha256": sha256(wheel),
                "projectUrl": f"https://pypi.org/project/{name}/{version}/",
            }
        )
    if not entries:
        raise SystemExit(f"wheelhouse is empty: {wheelhouse}")
    return {
        "schemaVersion": 1,
        "artifactType": "python-wheelhouse",
        "artifacts": entries,
    }


def canonical(data: dict) -> str:
    return json.dumps(data, ensure_ascii=False, indent=2, sort_keys=True) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Generate or verify a pinned Python wheelhouse provenance lock"
    )
    parser.add_argument("--wheelhouse", required=True, type=Path)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--write", type=Path)
    group.add_argument("--verify", type=Path)
    args = parser.parse_args()

    current = snapshot(args.wheelhouse)
    current_text = canonical(current)
    if args.write:
        args.write.parent.mkdir(parents=True, exist_ok=True)
        args.write.write_text(current_text, encoding="utf-8")
        print(f"WHEELHOUSE_LOCK_WRITTEN={args.write}")
        print(f"WHEELHOUSE_ARTIFACTS={len(current['artifacts'])}")
        print(f"WHEELHOUSE_LOCK_SHA256={hashlib.sha256(current_text.encode()).hexdigest()}")
        return 0

    expected_text = args.verify.read_text(encoding="utf-8")
    expected = json.loads(expected_text)
    if expected != current:
        expected_items = {
            item["file"]: item for item in expected.get("artifacts", [])
        }
        current_items = {
            item["file"]: item for item in current.get("artifacts", [])
        }
        missing = sorted(set(expected_items) - set(current_items))
        extra = sorted(set(current_items) - set(expected_items))
        changed = sorted(
            name
            for name in set(expected_items) & set(current_items)
            if expected_items[name] != current_items[name]
        )
        raise SystemExit(
            "wheelhouse lock mismatch: "
            f"missing={missing} extra={extra} changed={changed}"
        )
    print("WHEELHOUSE_LOCK_OK")
    print(f"WHEELHOUSE_ARTIFACTS={len(current['artifacts'])}")
    print(f"WHEELHOUSE_LOCK_SHA256={hashlib.sha256(expected_text.encode()).hexdigest()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
