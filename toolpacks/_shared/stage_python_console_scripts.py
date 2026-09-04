#!/usr/bin/env python3
from __future__ import annotations

import argparse
import configparser
import json
import re
from pathlib import Path

NORMALIZE = re.compile(r"[-_.]+")


def normalized(name: str) -> str:
    return NORMALIZE.sub("-", name).lower()


def find_dist_info(python_root: Path, distribution: str) -> Path:
    wanted = normalized(distribution)
    matches = []
    for path in python_root.glob("*.dist-info"):
        stem = path.name.removesuffix(".dist-info")
        package_name = stem.rsplit("-", 1)[0]
        if normalized(package_name) == wanted:
            matches.append(path)
    if len(matches) != 1:
        raise SystemExit(f"expected one dist-info for {distribution}, got: {matches}")
    return matches[0]


def parse_console_scripts(dist_info: Path) -> dict[str, str]:
    path = dist_info / "entry_points.txt"
    if not path.is_file():
        return {}
    parser = configparser.ConfigParser(interpolation=None)
    parser.optionxform = str
    parser.read(path, encoding="utf-8")
    if not parser.has_section("console_scripts"):
        return {}
    return dict(parser.items("console_scripts"))


def wrapper_text(
    entry_name: str,
    entry_point: str,
    python_dir: str,
    interpreter: str,
) -> str:
    module, target = entry_point.split(":", 1)
    target = target.split("[", 1)[0]
    return f"""#!{interpreter}
from __future__ import annotations
import importlib
import os
import sys

root = os.path.dirname(os.path.dirname(os.path.realpath(__file__)))
sys.path.insert(0, os.path.join(root, {python_dir!r}))
obj = importlib.import_module({module!r})
for part in {target!r}.split("."):
    obj = getattr(obj, part)
sys.argv[0] = {entry_name!r}
raise SystemExit(obj())
"""


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Generate wrappers for every upstream Python console_script"
    )
    parser.add_argument("--payload-root", required=True, type=Path)
    parser.add_argument("--distribution", action="append", required=True)
    parser.add_argument("--python-dir", default="python")
    parser.add_argument("--interpreter", default="/usr/bin/python3")
    parser.add_argument("--json-output", type=Path)
    args = parser.parse_args()

    python_root = args.payload_root / args.python_dir
    bin_root = args.payload_root / "bin"
    bin_root.mkdir(parents=True, exist_ok=True)
    all_scripts: dict[str, dict[str, str]] = {}
    for distribution in args.distribution:
        dist_info = find_dist_info(python_root, distribution)
        scripts = parse_console_scripts(dist_info)
        for name, target in sorted(scripts.items()):
            current = {"distribution": distribution, "entryPoint": target}
            existing = all_scripts.get(name)
            if existing and existing != current:
                raise SystemExit(f"console script collision for {name}: {existing} vs {current}")
            all_scripts[name] = current
            output = bin_root / name
            output.write_text(
                wrapper_text(name, target, args.python_dir, args.interpreter),
                encoding="utf-8",
            )
            output.chmod(0o755)

    if args.json_output:
        args.json_output.parent.mkdir(parents=True, exist_ok=True)
        args.json_output.write_text(
            json.dumps(all_scripts, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
