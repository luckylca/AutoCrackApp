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
    parser.add_argument("--tcpdump", required=True)
    parser.add_argument("--tcpdump-sha256", required=True)
    parser.add_argument("--tcpdump-version", default="4.99.5")
    parser.add_argument("--tcpdump-source-sha256", required=True)
    parser.add_argument("--libpcap-version", default="1.10.5")
    parser.add_argument("--libpcap-source-sha256", required=True)
    parser.add_argument("--version", default="tcpdump-4.99.5_libpcap-1.10.5_autocrack-1.1.0")
    parser.add_argument("--output-dir", required=True)
    args = parser.parse_args()
    binary = Path(args.tcpdump).resolve()
    actual = sha256(binary)
    if actual != args.tcpdump_sha256.lower():
        raise SystemExit(f"tcpdump SHA-256 mismatch: {actual}")
    with tempfile.TemporaryDirectory() as tmp:
        payload = Path(tmp) / "payload"
        (payload / "host-bin").mkdir(parents=True)
        (payload / "bin").mkdir(parents=True)
        shutil.copy2(binary, payload / "host-bin" / "tcpdump")
        shutil.copy2(Path(__file__).resolve().parent / "bin" / "tcpdump", payload / "bin" / "tcpdump")
        manifest = {
            "schemaVersion": 1,
            "id": "android-pcap-helper",
            "title": "Android host tcpdump",
            "version": args.version,
            "architecture": "arm64",
            "payloadEntry": "payload.zip",
            "payloadSha256": "0" * 64,
            "payloadSizeBytes": 1,
            "requiredPaths": ["bin/tcpdump", "host-bin/tcpdump"],
            "commands": [{"name": "tcpdump", "relativePath": "bin/tcpdump"}],
            "selfTests": [
                {"id": "tcpdump-binary", "title": "Android tcpdump binary", "command": "test -x /opt/autocrack/toolpacks/active/android-pcap-helper/host-bin/tcpdump && printf 'AUTOCRACK_TCPDUMP_BINARY_OK\n'", "expectedExitCodes": [0], "outputContains": ["AUTOCRACK_TCPDUMP_BINARY_OK"]},
                {"id": "tcpdump-launcher", "title": "Standard tcpdump Android-host launcher", "command": "test -x /opt/autocrack/toolpacks/active/android-pcap-helper/bin/tcpdump && grep -F '\"$@\"' /opt/autocrack/toolpacks/active/android-pcap-helper/bin/tcpdump", "expectedExitCodes": [0], "outputContains": ["$@"]},
            ],
            "sources": [
                {"name": "tcpdump", "version": args.tcpdump_version, "url": "https://www.tcpdump.org/release/tcpdump-%s.tar.xz" % args.tcpdump_version, "sha256": args.tcpdump_source_sha256.lower()},
                {"name": "libpcap", "version": args.libpcap_version, "url": "https://www.tcpdump.org/release/libpcap-%s.tar.xz" % args.libpcap_version, "sha256": args.libpcap_source_sha256.lower()},
            ],
        }
        write_outer(manifest, payload, Path(args.output_dir), "AutoCrackApp-android-pcap-helper-toolpack.zip", {"bin/tcpdump", "host-bin/tcpdump"})

if __name__ == "__main__":
    main()
