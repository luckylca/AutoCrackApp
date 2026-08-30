#!/usr/bin/env python3
import argparse
import hashlib
import json
import shutil
import stat
import tempfile
import zipfile
from pathlib import Path

FIXED_TIME = (1980, 1, 1, 0, 0, 0)

def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()

def deterministic_zip(root: Path, output: Path, executables: set[str]) -> None:
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED) as archive:
        for path in sorted(p for p in root.rglob("*") if p.is_file()):
            relative = path.relative_to(root).as_posix()
            info = zipfile.ZipInfo(relative, FIXED_TIME)
            info.create_system = 3
            info.compress_type = zipfile.ZIP_STORED
            mode = 0o755 if relative in executables or relative.startswith("bin/") or relative.startswith("host-bin/") else 0o644
            info.external_attr = (stat.S_IFREG | mode) << 16
            archive.writestr(info, path.read_bytes())

def write_outer(manifest: dict, payload_root: Path, output_dir: Path, filename: str, executables: set[str]) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        payload_zip = tmp / "payload.zip"
        deterministic_zip(payload_root, payload_zip, executables)
        manifest["payloadSha256"] = sha256(payload_zip)
        manifest["payloadSizeBytes"] = payload_zip.stat().st_size
        manifest_path = tmp / "manifest.json"
        manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        outer = output_dir / filename
        with zipfile.ZipFile(outer, "w", compression=zipfile.ZIP_STORED) as archive:
            for item in (manifest_path, payload_zip):
                info = zipfile.ZipInfo(item.name, FIXED_TIME)
                info.create_system = 3
                info.compress_type = zipfile.ZIP_STORED
                info.external_attr = (stat.S_IFREG | 0o644) << 16
                archive.writestr(info, item.read_bytes())
        shutil.copy2(manifest_path, output_dir / "manifest.json")
        shutil.copy2(payload_zip, output_dir / "payload.zip")
        (output_dir / "payload.sha256").write_text(manifest["payloadSha256"] + "\n", encoding="utf-8")
        (output_dir / "payload.size").write_text(str(manifest["payloadSizeBytes"]) + "\n", encoding="utf-8")
        (output_dir / f"{outer.name}.sha256").write_text(
            f"{sha256(outer)}  {outer.name}\n",
            encoding="utf-8",
        )
        print(f"TOOLPACK={outer}")
        print(f"PAYLOAD_SHA256={manifest['payloadSha256']}")
        print(f"PAYLOAD_SIZE={manifest['payloadSizeBytes']}")
        print(f"TOOLPACK_SHA256={sha256(outer)}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--lldb-server", required=True)
    parser.add_argument("--lldb-server-sha256", required=True)
    parser.add_argument("--lldb-client-root", required=True)
    parser.add_argument("--lldb-client-source-sha256", required=True)
    parser.add_argument("--python3-lldb-source-sha256", required=True)
    parser.add_argument("--liblldb-source-sha256", required=True)
    parser.add_argument("--libclang-cpp14-source-sha256", required=True)
    parser.add_argument("--libllvm14-source-sha256", required=True)
    parser.add_argument("--python3-six-source-sha256", required=True)
    parser.add_argument("--output-dir", required=True)
    args = parser.parse_args()
    binary = Path(args.lldb_server).resolve()
    actual = sha256(binary)
    if actual != args.lldb_server_sha256.lower():
        raise SystemExit(f"lldb-server SHA-256 mismatch: {actual}")
    client_root = Path(args.lldb_client_root).resolve()
    client_binary = client_root / "lib" / "llvm-14" / "bin" / "lldb"
    if not client_binary.is_file():
        raise SystemExit("lldb client root is missing lib/llvm-14/bin/lldb")
    version = "android-llvm-r522817_lldb-14_autocrack-2.0.0"
    with tempfile.TemporaryDirectory() as tmp:
        payload = Path(tmp) / "payload"
        (payload / "host-bin").mkdir(parents=True)
        (payload / "bin").mkdir(parents=True)
        shutil.copy2(binary, payload / "host-bin" / "lldb-server-android")
        shutil.copytree(client_root / "lib", payload / "lib")
        root = Path(__file__).resolve().parent
        shutil.copy2(root / "bin" / "lldb", payload / "bin" / "lldb")
        shutil.copy2(root / "bin" / "android-lldb-server", payload / "bin" / "android-lldb-server")
        manifest = {
            "schemaVersion": 1,
            "id": "android-lldb-server",
            "title": "Standard LLDB client and Android server",
            "version": version,
            "architecture": "arm64",
            "payloadEntry": "payload.zip",
            "payloadSha256": "0" * 64,
            "payloadSizeBytes": 1,
            "requiredPaths": ["bin/lldb", "bin/android-lldb-server", "host-bin/lldb-server-android", "lib/llvm-14/bin/lldb", "lib/llvm-14/lib/python3.11/dist-packages/six.py"],
            "commands": [
                {"name": "lldb", "relativePath": "bin/lldb"},
                {"name": "android-lldb-server", "relativePath": "bin/android-lldb-server"},
            ],
            "selfTests": [
                {"id": "lldb-server-android-binary", "title": "Android LLDB server payload", "command": "test -x /opt/autocrack/toolpacks/active/android-lldb-server/host-bin/lldb-server-android && printf 'AUTOCRACK_LLDB_ANDROID_BINARY_OK\n'", "expectedExitCodes": [0], "outputContains": ["AUTOCRACK_LLDB_ANDROID_BINARY_OK"]},
                {"id": "lldb-client-version", "title": "Standard Debian LLDB client", "command": "lldb --version", "expectedExitCodes": [0], "outputContains": ["lldb version 14.0.6"]},
                {"id": "lldb-python-runtime", "title": "LLDB Python runtime", "command": "lldb --batch -o 'script import lldb, six; print(\"AUTOCRACK_LLDB_PYTHON_OK\", six.__version__)'", "expectedExitCodes": [0], "outputContains": ["AUTOCRACK_LLDB_PYTHON_OK 1.16.0"]},
            ],
            "sources": [
                {"name": "lldb-server", "version": "android-llvm-r522817-autocrack-seize-runtime-stop", "url": "https://android.googlesource.com/toolchain/llvm-project/+/d8003a456d14a3deb8054cdaa529ffbf02d9b262", "sha256": actual},
                {"name": "debian-lldb-14-arm64", "version": "1:14.0.6-12", "url": "https://deb.debian.org/debian/pool/main/l/llvm-toolchain-14/lldb-14_14.0.6-12_arm64.deb", "sha256": args.lldb_client_source_sha256.lower()},
                {"name": "debian-python3-lldb-14-arm64", "version": "1:14.0.6-12", "url": "https://deb.debian.org/debian/pool/main/l/llvm-toolchain-14/python3-lldb-14_14.0.6-12_arm64.deb", "sha256": args.python3_lldb_source_sha256.lower()},
                {"name": "debian-liblldb-14-arm64", "version": "1:14.0.6-12", "url": "https://deb.debian.org/debian/pool/main/l/llvm-toolchain-14/liblldb-14_14.0.6-12_arm64.deb", "sha256": args.liblldb_source_sha256.lower()},
                {"name": "debian-libclang-cpp14-arm64", "version": "1:14.0.6-12", "url": "https://deb.debian.org/debian/pool/main/l/llvm-toolchain-14/libclang-cpp14_14.0.6-12_arm64.deb", "sha256": args.libclang_cpp14_source_sha256.lower()},
                {"name": "debian-libllvm14-arm64", "version": "1:14.0.6-12", "url": "https://deb.debian.org/debian/pool/main/l/llvm-toolchain-14/libllvm14_14.0.6-12_arm64.deb", "sha256": args.libllvm14_source_sha256.lower()},
                {"name": "debian-python3-six", "version": "1.16.0-4", "url": "https://deb.debian.org/debian/pool/main/s/six/python3-six_1.16.0-4_all.deb", "sha256": args.python3_six_source_sha256.lower()},
            ],
        }
        executables = {"bin/lldb", "bin/android-lldb-server", "host-bin/lldb-server-android", "lib/llvm-14/bin/lldb", "lib/llvm-14/bin/lldb-argdumper"}
        write_outer(manifest, payload, Path(args.output_dir), "AutoCrackApp-android-lldb-server-toolpack.zip", executables)

if __name__ == "__main__":
    main()
