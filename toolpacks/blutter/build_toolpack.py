#!/usr/bin/env python3
from __future__ import annotations

import argparse
import shutil
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SHARED = ROOT.parent / "_shared"
sys.path.insert(0, str(SHARED))

from python_wheel_toolpack import require_sha256, write_toolpack  # noqa: E402

TOOLPACK_ID = "blutter"
UPSTREAM_COMMIT = "4a60ac648bf448c5a7596437243bcd0b9376fdf0"
VERSION = f"blutter-{UPSTREAM_COMMIT}_autocrack-1.0.0"
SOURCE_NAME = f"blutter-{UPSTREAM_COMMIT}.zip"
SOURCE_URL = f"https://codeload.github.com/worawit/blutter/zip/{UPSTREAM_COMMIT}"
SOURCE_SHA256 = "f48e5a0d767dd5bb3dcd999afd45436c6de0f8b981a3cebe689750dc1a2af61f"

UPSTREAM_CLANG_BLOCK = """if (CMAKE_CXX_COMPILER_ID STREQUAL "Clang")
	# for macOS only
	cmake_path(GET CMAKE_CXX_COMPILER PARENT_PATH LLVM_BIN_DIR)
	# <format> library is experimental in clang 15 and 16
	add_compile_options(-fexperimental-library)
	# need an extra lib for experimental library
	add_link_options(-fexperimental-library -L${LLVM_BIN_DIR}/../lib/c++ -dead_strip)
endif()
"""

AUTOCRACK_CLANG_BLOCK = """if (APPLE AND CMAKE_CXX_COMPILER_ID STREQUAL "Clang")
	# Upstream macOS path.
	cmake_path(GET CMAKE_CXX_COMPILER PARENT_PATH LLVM_BIN_DIR)
	# <format> library is experimental in clang 15 and 16
	add_compile_options(-fexperimental-library)
	# need an extra lib for experimental library
	add_link_options(-fexperimental-library -L${LLVM_BIN_DIR}/../lib/c++ -dead_strip)
elseif (CMAKE_SYSTEM_NAME STREQUAL "Linux" AND CMAKE_CXX_COMPILER_ID STREQUAL "Clang")
	# AutoCrack Debian ARM64 compatibility: use Bookworm's libc++16 and never
	# pass the macOS-only -dead_strip linker flag on Linux.
	add_compile_options(-stdlib=libc++ -fexperimental-library)
	add_link_options(-stdlib=libc++ -fexperimental-library)
endif()
"""


def safe_extract_source(archive: Path, target: Path) -> Path:
    target.mkdir(parents=True, exist_ok=True)
    expected_root = f"blutter-{UPSTREAM_COMMIT}"
    with zipfile.ZipFile(archive) as source:
        for info in source.infolist():
            name = info.filename
            if name.startswith("/") or ".." in Path(name).parts:
                raise SystemExit(f"unsafe Blutter zip member: {name}")
            if Path(name).parts and Path(name).parts[0] != expected_root:
                raise SystemExit(f"unexpected Blutter archive root: {name}")
        source.extractall(target)
    root = target / expected_root
    if not root.is_dir():
        raise SystemExit(f"Blutter source root missing: {root}")
    return root


def apply_linux_clang_patch(source_root: Path) -> None:
    cmake = source_root / "blutter" / "CMakeLists.txt"
    text = cmake.read_text(encoding="utf-8")
    if text.count(UPSTREAM_CLANG_BLOCK) != 1:
        raise SystemExit("expected upstream Blutter Clang block was not found exactly once")
    cmake.write_text(
        text.replace(UPSTREAM_CLANG_BLOCK, AUTOCRACK_CLANG_BLOCK),
        encoding="utf-8",
    )


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Build complete upstream Blutter source Toolpack"
    )
    parser.add_argument("--source-zip", required=True, type=Path)
    parser.add_argument("--output-dir", type=Path, default=ROOT / "dist")
    args = parser.parse_args()

    if args.source_zip.name != SOURCE_NAME:
        raise SystemExit(f"unexpected Blutter source filename: {args.source_zip.name}")
    require_sha256(args.source_zip, SOURCE_SHA256)

    with tempfile.TemporaryDirectory() as temporary_directory:
        temporary = Path(temporary_directory)
        source_root = safe_extract_source(args.source_zip, temporary / "src")
        apply_linux_clang_patch(source_root)

        payload = temporary / "payload"
        upstream = payload / "upstream"
        shutil.copytree(
            source_root,
            upstream,
            ignore=shutil.ignore_patterns("__pycache__", "*.pyc", ".DS_Store"),
        )
        (upstream / ".autocrack-source-revision").write_text(
            UPSTREAM_COMMIT + "\n",
            encoding="utf-8",
        )

        bin_root = payload / "bin"
        bin_root.mkdir(parents=True)
        shutil.copy2(ROOT / "bin" / "blutter", bin_root / "blutter")
        (bin_root / "blutter").chmod(0o755)
        shutil.copy2(ROOT / "SKILL.md", payload / "SKILL.md")
        shutil.copy2(ROOT / "VERSION", payload / "VERSION")
        shutil.copy2(ROOT / "AUTOCRACK_PATCH.md", payload / "AUTOCRACK_PATCH.md")

        required = [
            upstream / "blutter.py",
            upstream / "dartvm_fetch_build.py",
            upstream / "extract_dart_info.py",
            upstream / "blutter" / "CMakeLists.txt",
            upstream / "scripts" / "frida.template.js",
            upstream / "scripts" / "dartvm_create_srclist.py",
        ]
        for path in required:
            if not path.is_file():
                raise SystemExit(f"missing upstream Blutter file: {path}")

        manifest = {
            "schemaVersion": 2,
            "id": TOOLPACK_ID,
            "title": "Blutter complete Flutter Dart AOT analysis pipeline",
            "version": VERSION,
            "description": (
                "Complete upstream Blutter source/pipeline at an immutable commit, "
                "with a documented Linux Clang-16 compatibility patch and persistent "
                "Dart VM build cache."
            ),
            "architecture": "arm64",
            "payloadEntry": "payload.zip",
            "payloadSha256": "0" * 64,
            "payloadSizeBytes": 1,
            "requiredPaths": [
                "bin/blutter",
                "upstream/blutter.py",
                "upstream/dartvm_fetch_build.py",
                "upstream/extract_dart_info.py",
                "upstream/blutter/CMakeLists.txt",
                "upstream/scripts/frida.template.js",
                "upstream/scripts/dartvm_create_srclist.py",
                "upstream/.autocrack-source-revision",
                "AUTOCRACK_PATCH.md",
                "SKILL.md",
                "VERSION",
            ],
            "commands": [
                {
                    "name": "blutter",
                    "relativePath": "bin/blutter",
                    "description": "Complete upstream blutter.py CLI with persistent build state.",
                },
            ],
            "selfTests": [
                {
                    "id": "blutter-cli",
                    "title": "Complete upstream Blutter CLI",
                    "command": "blutter --help",
                    "expectedExitCodes": [0],
                    "outputContains": [
                        "Reversing a flutter application tool",
                        "--dart-version",
                        "--rebuild",
                        "--no-analysis",
                    ],
                },
                {
                    "id": "blutter-toolchain",
                    "title": "Linux ARM64 Blutter compiler and Python dependencies",
                    "command": (
                        "clang++-16 --version >/dev/null && cmake --version >/dev/null && "
                        "ninja --version >/dev/null && pkg-config --exists capstone && "
                        "PYTHONDONTWRITEBYTECODE=1 python3 -B -c "
                        "\"import elftools,requests;print('AUTOCRACK_BLUTTER_TOOLCHAIN_OK')\""
                    ),
                    "expectedExitCodes": [0],
                    "outputContains": ["AUTOCRACK_BLUTTER_TOOLCHAIN_OK"],
                },
                {
                    "id": "blutter-source",
                    "title": "Pinned full upstream Blutter source and patch",
                    "command": (
                        "test -s /opt/autocrack/toolpacks/active/blutter/upstream/blutter.py && "
                        "test -s /opt/autocrack/toolpacks/active/blutter/upstream/dartvm_fetch_build.py && "
                        "test -s /opt/autocrack/toolpacks/active/blutter/upstream/scripts/frida.template.js && "
                        "grep -F 'AutoCrack Debian ARM64 compatibility' "
                        "/opt/autocrack/toolpacks/active/blutter/upstream/blutter/CMakeLists.txt && "
                        "printf 'AUTOCRACK_BLUTTER_SOURCE_OK\\n'"
                    ),
                    "expectedExitCodes": [0],
                    "outputContains": ["AUTOCRACK_BLUTTER_SOURCE_OK"],
                },
            ],
            "sources": [
                {
                    "name": "blutter-source",
                    "version": UPSTREAM_COMMIT,
                    "url": SOURCE_URL,
                    "sha256": SOURCE_SHA256,
                },
            ],
            "requires": {
                "runtime": None,
                "capabilities": [],
                "commands": [
                    "clang-16",
                    "clang++-16",
                    "cmake",
                    "ninja",
                    "pkg-config",
                    "git",
                    "python3",
                ],
                "optionalCapabilities": [],
            },
        }

        output = write_toolpack(
            payload_root=payload,
            manifest=manifest,
            output_dir=args.output_dir,
            filename=f"AutoCrackApp-{TOOLPACK_ID}-{VERSION}-toolpack.zip",
            executables={"bin/blutter"},
        )
        print(f"UPSTREAM_COMMIT={UPSTREAM_COMMIT}")
        print(f"VERSION={VERSION}")
        print(f"OUTPUT={output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
