#!/usr/bin/env python3
from __future__ import annotations

import argparse
import ast
import shutil
import sys
import tarfile
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SHARED = ROOT.parent / "_shared"
sys.path.insert(0, str(SHARED))

from python_wheel_toolpack import require_sha256, write_toolpack  # noqa: E402

TOOLPACK_ID = "frida-il2cpp-bridge"
UPSTREAM_VERSION = "0.13.2"
VERSION = "frida-il2cpp-bridge-0.13.2_autocrack-1.0.0"
NPM_NAME = "frida-il2cpp-bridge-0.13.2.tgz"
NPM_URL = "https://registry.npmjs.org/frida-il2cpp-bridge/-/frida-il2cpp-bridge-0.13.2.tgz"
NPM_SHA256 = "298430a57a9d713feedf2b26bd0495becf2823240429e6408545c86381ac8060"


def safe_extract_npm(archive: Path, target: Path) -> Path:
    target.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive, "r:gz") as source:
        members = source.getmembers()
        for member in members:
            parts = Path(member.name).parts
            if member.name.startswith("/") or ".." in parts:
                raise SystemExit(f"unsafe npm member: {member.name}")
            if not parts or parts[0] != "package":
                raise SystemExit(f"unexpected npm archive root: {member.name}")
            if not (member.isfile() or member.isdir()):
                raise SystemExit(f"unexpected npm member type: {member.name}")
        source.extractall(target)
    root = target / "package"
    if not root.is_dir():
        raise SystemExit("npm package root missing")
    return root


def replace_exact(path: Path, old: str, new: str, expected_count: int = 1) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != expected_count:
        raise SystemExit(
            f"compatibility patch mismatch in {path}: expected {expected_count}, got {count}"
        )
    path.write_text(text.replace(old, new), encoding="utf-8")


def remove_override_decorators(path: Path, expected_count: int) -> None:
    replace_exact(path, "    @override\n", "", expected_count)


def apply_python311_typing_patch(package_root: Path) -> None:
    app = package_root / "cli" / "src" / "app.py"
    util_app = package_root / "cli" / "src" / "utils" / "app.py"
    models = package_root / "cli" / "src" / "dump" / "models.py"
    command = package_root / "cli" / "src" / "dump" / "command.py"
    io = package_root / "cli" / "src" / "utils" / "io.py"

    replace_exact(
        app,
        "from typing import Mapping, TypedDict, override\n",
        "from typing import Mapping, TypedDict\n",
    )
    remove_override_decorators(app, 5)
    replace_exact(
        app,
        """class FridaIl2CppBridgeCommand[
    SendPayload: Mapping[str, object],
    ExitPayload: Mapping[str, object],
](Command[FridaIl2CppBridgeApplication, SendPayload, ExitPayload]):
    pass
""",
        """class FridaIl2CppBridgeCommand(Command):
    pass
""",
    )

    replace_exact(
        util_app,
        "from typing import Any, override, Mapping\n",
        "from typing import Any, Mapping\n",
    )
    remove_override_decorators(util_app, 1)
    replace_exact(
        util_app,
        """class Command[
    T: "Application",
    SendPayload: Mapping[str, object],
    ExitPayload: Mapping[str, object],
](ABC):
""",
        """class Command(ABC):
""",
    )

    replace_exact(
        models,
        "type AssemblyHandle = str\n\n\ntype ClassHandle = str\n",
        "AssemblyHandle = str\n\n\nClassHandle = str\n",
    )

    replace_exact(command, "from typing import override\n\n", "")
    remove_override_decorators(command, 3)
    replace_exact(
        command,
        "class DumpCommand(FridaIl2CppBridgeCommand[AssemblyDump | ClassDump, dict]):\n",
        "class DumpCommand(FridaIl2CppBridgeCommand):\n",
    )

    replace_exact(io, "from typing import override\n", "")
    remove_override_decorators(io, 4)


def assert_python311_grammar(package_root: Path) -> None:
    failures = []
    for path in sorted((package_root / "cli").rglob("*.py")):
        source = path.read_text(encoding="utf-8")
        try:
            ast.parse(source, filename=str(path), feature_version=(3, 11))
        except SyntaxError as exc:
            failures.append(f"{path}: {exc}")
    if failures:
        raise SystemExit("Python 3.11 grammar validation failed:\n" + "\n".join(failures))


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Build complete frida-il2cpp-bridge AutoCrack Toolpack"
    )
    parser.add_argument("--npm-tgz", required=True, type=Path)
    parser.add_argument("--output-dir", type=Path, default=ROOT / "dist")
    args = parser.parse_args()

    if args.npm_tgz.name != NPM_NAME:
        raise SystemExit(f"unexpected npm filename: {args.npm_tgz.name}")
    require_sha256(args.npm_tgz, NPM_SHA256)

    with tempfile.TemporaryDirectory() as temporary_directory:
        temporary = Path(temporary_directory)
        original = safe_extract_npm(args.npm_tgz, temporary / "npm-original")

        payload = temporary / "payload"
        shutil.copytree(original, payload)
        shutil.copytree(
            original,
            payload / "upstream-original",
            ignore=shutil.ignore_patterns("__pycache__", "*.pyc"),
        )

        apply_python311_typing_patch(payload)
        assert_python311_grammar(payload)

        bin_root = payload / "bin"
        bin_root.mkdir(parents=True)
        shutil.copy2(
            ROOT / "bin" / "frida-il2cpp-bridge",
            bin_root / "frida-il2cpp-bridge",
        )
        (bin_root / "frida-il2cpp-bridge").chmod(0o755)
        shutil.copy2(ROOT / "SKILL.md", payload / "SKILL.md")
        shutil.copy2(ROOT / "VERSION", payload / "VERSION")
        shutil.copy2(ROOT / "AUTOCRACK_PATCH.md", payload / "AUTOCRACK_PATCH.md")

        required_files = (
            payload / "package.json",
            payload / "dist" / "index.js",
            payload / "dist" / "index.js.map",
            payload / "dist" / "index.d.ts",
            payload / "cli" / "main.py",
            payload / "cli" / "src" / "app.py",
            payload / "cli" / "src" / "dump" / "agent.js",
            payload / "upstream-original" / "cli" / "main.py",
            payload / "upstream-original" / "dist" / "index.js",
        )
        for required in required_files:
            if not required.is_file():
                raise SystemExit(f"missing upstream IL2CPP bridge file: {required}")

        manifest = {
            "schemaVersion": 2,
            "id": TOOLPACK_ID,
            "title": "frida-il2cpp-bridge complete IL2CPP runtime toolkit",
            "version": VERSION,
            "description": (
                "Complete official frida-il2cpp-bridge 0.13.2 npm package, upstream "
                "dump CLI and compiled JS/type surface, with a documented Python 3.11 "
                "typing-syntax compatibility patch and the untouched npm package retained."
            ),
            "architecture": "arm64",
            "payloadEntry": "payload.zip",
            "payloadSha256": "0" * 64,
            "payloadSizeBytes": 1,
            "requiredPaths": [
                "bin/frida-il2cpp-bridge",
                "package.json",
                "dist/index.js",
                "dist/index.js.map",
                "dist/index.d.ts",
                "cli/main.py",
                "cli/src/app.py",
                "cli/src/dump/agent.js",
                "upstream-original/package.json",
                "upstream-original/dist/index.js",
                "upstream-original/cli/main.py",
                "AUTOCRACK_PATCH.md",
                "SKILL.md",
                "VERSION",
            ],
            "commands": [
                {
                    "name": "frida-il2cpp-bridge",
                    "relativePath": "bin/frida-il2cpp-bridge",
                    "description": "Upstream frida-il2cpp-bridge CLI using AutoCrack Frida transport.",
                },
            ],
            "selfTests": [
                {
                    "id": "frida-il2cpp-cli",
                    "title": "Upstream IL2CPP bridge CLI",
                    "command": "frida-il2cpp-bridge --help",
                    "expectedExitCodes": [0],
                    "outputContains": ["IL2CPP options", "dump"],
                },
                {
                    "id": "frida-il2cpp-version",
                    "title": "Upstream bridge and Frida version reporting",
                    "command": "frida-il2cpp-bridge --version",
                    "expectedExitCodes": [0],
                    "outputContains": ["frida-il2cpp-bridge", "0.13.2"],
                },
                {
                    "id": "frida-il2cpp-library",
                    "title": "Complete compiled Il2Cpp runtime library surface",
                    "command": (
                        "grep -F 'globalThis.Il2Cpp = Il2Cpp' "
                        "/opt/autocrack/toolpacks/active/frida-il2cpp-bridge/dist/index.js "
                        ">/dev/null && grep -F 'function perform' "
                        "/opt/autocrack/toolpacks/active/frida-il2cpp-bridge/dist/index.d.ts "
                        ">/dev/null && grep -F 'function trace' "
                        "/opt/autocrack/toolpacks/active/frida-il2cpp-bridge/dist/index.d.ts "
                        ">/dev/null && printf 'AUTOCRACK_IL2CPP_LIBRARY_OK\\n'"
                    ),
                    "expectedExitCodes": [0],
                    "outputContains": ["AUTOCRACK_IL2CPP_LIBRARY_OK"],
                },
                {
                    "id": "frida-il2cpp-python311",
                    "title": "Patched upstream CLI imports on rootfs Python 3.11",
                    "command": (
                        "PYTHONDONTWRITEBYTECODE=1 python3 -B -c "
                        "\"import sys;sys.path[:0]=['/opt/autocrack/toolpacks/active/frida-il2cpp-bridge/cli','/opt/autocrack/toolpacks/active/android-frida/python'];"
                        "import src.app,src.dump.command;"
                        "print('AUTOCRACK_IL2CPP_PY311_OK')\""
                    ),
                    "expectedExitCodes": [0],
                    "outputContains": ["AUTOCRACK_IL2CPP_PY311_OK"],
                },
            ],
            "sources": [
                {
                    "name": "frida-il2cpp-bridge-npm",
                    "version": UPSTREAM_VERSION,
                    "url": NPM_URL,
                    "sha256": NPM_SHA256,
                },
            ],
            "requires": {
                "runtime": None,
                "capabilities": [],
                "commands": ["frida", "android-frida-server"],
                "optionalCapabilities": [],
            },
        }

        output = write_toolpack(
            payload_root=payload,
            manifest=manifest,
            output_dir=args.output_dir,
            filename=f"AutoCrackApp-{TOOLPACK_ID}-{VERSION}-toolpack.zip",
            executables={"bin/frida-il2cpp-bridge"},
        )
        print(f"UPSTREAM_VERSION={UPSTREAM_VERSION}")
        print(f"VERSION={VERSION}")
        print(f"OUTPUT={output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
