#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def wheel_identity(filename: str) -> tuple[str, str]:
    if not filename.endswith(".whl"):
        raise ValueError(f"not a wheel: {filename}")
    stem = filename[:-4]
    match = re.match(r"(?P<name>.+?)-(?P<version>[0-9][^-]*)-", stem)
    if not match:
        raise ValueError(f"cannot parse wheel filename: {filename}")
    return match.group("name").replace("_", "-"), match.group("version")


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Create a deterministic source/hash lock for a downloaded Python wheelhouse."
    )
    parser.add_argument("--wheelhouse", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--python", required=True)
    parser.add_argument("--platform", required=True)
    args = parser.parse_args()

    wheels = []
    for path in sorted(args.wheelhouse.glob("*.whl"), key=lambda item: item.name.lower()):
        name, version = wheel_identity(path.name)
        wheels.append(
            {
                "filename": path.name,
                "name": name,
                "version": version,
                "url": f"https://pypi.org/project/{name}/{version}/",
                "sha256": sha256(path),
            }
        )
    if not wheels:
        raise SystemExit("wheelhouse contains no wheels")
    data = {
        "schemaVersion": 1,
        "python": args.python,
        "platform": args.platform,
        "wheels": wheels,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(data, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(f"WHEELS={len(wheels)}")
    print(f"LOCK={args.output}")
    print(f"LOCK_SHA256={sha256(args.output)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
