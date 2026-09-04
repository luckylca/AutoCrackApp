#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "toolpacks" / "_shared"))

from python_wheel_toolpack import load_locked_wheelhouse  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Download and verify every exact wheel declared by an AutoCrack wheelhouse lock."
    )
    parser.add_argument("--lock", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    args = parser.parse_args()

    lock = json.loads(args.lock.read_text(encoding="utf-8"))
    python_version = str(lock.get("python", ""))
    platform = str(lock.get("platform", ""))
    wheels = lock.get("wheels")
    if not python_version or not platform or not isinstance(wheels, list) or not wheels:
        raise SystemExit(f"invalid wheelhouse lock: {args.lock}")

    python_digits = python_version.replace(".", "")
    if not python_digits.isdigit():
        raise SystemExit(f"unsupported python version in lock: {python_version}")
    abi = f"cp{python_digits}"
    specs = [f"{entry['name']}=={entry['version']}" for entry in wheels]

    args.output_dir.mkdir(parents=True, exist_ok=True)
    for child in args.output_dir.iterdir():
        if child.is_file():
            child.unlink()

    command = [
        sys.executable,
        "-m",
        "pip",
        "download",
        "--only-binary=:all:",
        "--no-deps",
        "--platform",
        platform,
        "--python-version",
        python_digits,
        "--implementation",
        "cp",
        "--abi",
        abi,
        "--dest",
        str(args.output_dir),
        *specs,
    ]
    subprocess.run(command, check=True)
    verified, entries = load_locked_wheelhouse(args.lock, args.output_dir)
    print(f"LOCKED_WHEELHOUSE_OK wheels={len(verified)} entries={len(entries)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
