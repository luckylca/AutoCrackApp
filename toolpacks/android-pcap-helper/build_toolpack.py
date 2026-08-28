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
        print(f"TOOLPACK={outer}")
        print(f"PAYLOAD_SHA256={manifest['payloadSha256']}")
        print(f"PAYLOAD_SIZE={manifest['payloadSizeBytes']}")
        print(f"TOOLPACK_SHA256={sha256(outer)}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tcpdump", required=True)
    parser.add_argument("--tcpdump-sha256", required=True)
    parser.add_argument("--version", default="tcpdump-android-arm64-autocrack-1.0.0")
    parser.add_argument("--output-dir", required=True)
    args = parser.parse_args()
    binary = Path(args.tcpdump).resolve()
    actual = sha256(binary)
    if actual != args.tcpdump_sha256.lower():
        raise SystemExit(f"tcpdump SHA-256 mismatch: {actual}")
    with tempfile.TemporaryDirectory() as tmp:
        payload = Path(tmp) / "payload"
        (payload / "host-bin").mkdir(parents=True)
        shutil.copy2(binary, payload / "host-bin" / "tcpdump")
        manifest = {
            "schemaVersion": 1,
            "id": "android-pcap-helper",
            "title": "Android tcpdump bounded capture helper",
            "version": args.version,
            "architecture": "arm64",
            "payloadEntry": "payload.zip",
            "payloadSha256": "0" * 64,
            "payloadSizeBytes": 1,
            "requiredPaths": ["host-bin/tcpdump"],
            "commands": [{"name": "tcpdump-autocrack", "relativePath": "host-bin/tcpdump"}],
            "selfTests": [{"id": "tcpdump-binary", "title": "Android tcpdump binary", "command": "test -x /opt/autocrack/toolpacks/packs/android-pcap-helper/%s/host-bin/tcpdump && printf 'AUTOCRACK_TCPDUMP_BINARY_OK\n'" % args.version, "expectedExitCodes": [0], "outputContains": ["AUTOCRACK_TCPDUMP_BINARY_OK"]}],
            "sources": [{"name": "tcpdump-android-arm64", "version": args.version, "url": "https://www.tcpdump.org/", "sha256": actual}],
        }
        write_outer(manifest, payload, Path(args.output_dir), "AutoCrackApp-android-pcap-helper-toolpack.zip", {"host-bin/tcpdump"})

if __name__ == "__main__":
    main()
