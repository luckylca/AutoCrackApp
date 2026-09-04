#!/usr/bin/env python3
from __future__ import annotations

import argparse
import shutil
import sys
import tarfile
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SHARED = ROOT.parent / "_shared"
sys.path.insert(0, str(SHARED))

from python_wheel_toolpack import (  # noqa: E402
    require_sha256,
    write_toolpack,
)

TOOLPACK_ID = "mitmproxy"
UPSTREAM_VERSION = "12.2.3"
VERSION = "mitmproxy-12.2.3-linux-aarch64_autocrack-1.0.0"
ARCHIVE_NAME = "mitmproxy-12.2.3-linux-aarch64.tar.gz"
ARCHIVE_URL = (
    "https://downloads.mitmproxy.org/12.2.3/"
    "mitmproxy-12.2.3-linux-aarch64.tar.gz"
)
ARCHIVE_SHA256 = "b358643a6c4f4b39e33d985350f660b724fece95687d7daa899ef0c4e211f681"
UPSTREAM_COMMANDS = ("mitmproxy", "mitmdump", "mitmweb")


def extract_official_archive(archive: Path, target: Path) -> None:
    target.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive, "r:gz") as source:
        members = source.getmembers()
        names = {member.name for member in members}
        if names != set(UPSTREAM_COMMANDS):
            raise SystemExit(
                f"unexpected mitmproxy archive members: {sorted(names)}"
            )
        for member in members:
            if member.name.startswith("/") or ".." in Path(member.name).parts:
                raise SystemExit(f"unsafe mitmproxy tar member: {member.name}")
            if not member.isfile():
                raise SystemExit(
                    f"unexpected non-file mitmproxy member: {member.name}"
                )
        source.extractall(target)

    for command in UPSTREAM_COMMANDS:
        path = target / command
        if not path.is_file():
            raise SystemExit(f"official archive missing {command}")
        path.chmod(0o755)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Build official mitmproxy Linux aarch64 AutoCrack Toolpack"
    )
    parser.add_argument("--archive", required=True, type=Path)
    parser.add_argument("--output-dir", type=Path, default=ROOT / "dist")
    args = parser.parse_args()

    if args.archive.name != ARCHIVE_NAME:
        raise SystemExit(f"unexpected mitmproxy archive name: {args.archive.name}")
    require_sha256(args.archive, ARCHIVE_SHA256)

    with tempfile.TemporaryDirectory() as temporary_directory:
        temporary = Path(temporary_directory)
        extracted = temporary / "official"
        payload = temporary / "payload"
        bin_root = payload / "bin"
        bin_root.mkdir(parents=True)

        extract_official_archive(args.archive, extracted)
        for command in UPSTREAM_COMMANDS:
            shutil.copy2(extracted / command, bin_root / command)
            (bin_root / command).chmod(0o755)

        shutil.copy2(ROOT / "SKILL.md", payload / "SKILL.md")
        shutil.copy2(ROOT / "VERSION", payload / "VERSION")
        example_root = payload / "examples"
        example_root.mkdir(parents=True)
        shutil.copy2(
            ROOT / "examples" / "autocrack_addon_smoke.py",
            example_root / "autocrack_addon_smoke.py",
        )

        manifest = {
            "schemaVersion": 2,
            "id": TOOLPACK_ID,
            "title": "mitmproxy complete Linux ARM64 interception suite",
            "version": VERSION,
            "description": (
                "Official mitmproxy 12.2.3 Linux aarch64 standalone mitmproxy, "
                "mitmdump and mitmweb binaries with their embedded Python addon runtime."
            ),
            "architecture": "arm64",
            "payloadEntry": "payload.zip",
            "payloadSha256": "0" * 64,
            "payloadSizeBytes": 1,
            "requiredPaths": [
                "bin/mitmproxy",
                "bin/mitmdump",
                "bin/mitmweb",
                "examples/autocrack_addon_smoke.py",
                "SKILL.md",
                "VERSION",
            ],
            "commands": [
                {
                    "name": command,
                    "relativePath": f"bin/{command}",
                    "description": f"Official upstream {command} 12.2.3 standalone.",
                }
                for command in UPSTREAM_COMMANDS
            ],
            "selfTests": [
                {
                    "id": "mitmproxy-version",
                    "title": "Official mitmproxy standalone version",
                    "command": "mitmproxy --version",
                    "expectedExitCodes": [0],
                    "outputContains": ["Mitmproxy: 12.2.3"],
                },
                {
                    "id": "mitmdump-addon-api",
                    "title": "Embedded upstream Python addon API",
                    "command": (
                        "mitmdump -q -s /opt/autocrack/toolpacks/active/mitmproxy/"
                        "examples/autocrack_addon_smoke.py"
                    ),
                    "expectedExitCodes": [0],
                    "outputContains": ["AUTOCRACK_MITMPROXY_ADDON_API_OK"],
                },
                {
                    "id": "mitmweb-help",
                    "title": "Official mitmweb command",
                    "command": "mitmweb --help",
                    "expectedExitCodes": [0],
                    "outputContains": ["usage:", "mitmweb"],
                },
            ],
            "sources": [
                {
                    "name": "mitmproxy-linux-aarch64",
                    "version": UPSTREAM_VERSION,
                    "url": ARCHIVE_URL,
                    "sha256": ARCHIVE_SHA256,
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
            executables={f"bin/{command}" for command in UPSTREAM_COMMANDS},
        )
        print(f"VERSION={VERSION}")
        print(f"OUTPUT={output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
