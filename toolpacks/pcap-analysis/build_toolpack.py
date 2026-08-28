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
    parser.add_argument("--output-dir", required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent
    with tempfile.TemporaryDirectory() as tmp:
        payload = Path(tmp) / "payload"
        (payload / "bin").mkdir(parents=True)
        shutil.copy2(root / "bin" / "pcap-summary", payload / "bin" / "pcap-summary")
        manifest = {
            "schemaVersion": 1,
            "id": "rootfs-pcap-analysis",
            "title": "Rootfs pcap metadata analysis",
            "version": "pcap-summary-1.0.0",
            "architecture": "all",
            "payloadEntry": "payload.zip",
            "payloadSha256": "0" * 64,
            "payloadSizeBytes": 1,
            "requiredPaths": ["bin/pcap-summary"],
            "commands": [{"name": "pcap-summary", "relativePath": "bin/pcap-summary"}],
            "selfTests": [{"id": "pcap-summary-self-test", "title": "Pure Python pcap summary helper", "command": "pcap-summary --self-test", "expectedExitCodes": [0], "outputContains": ["PCAP_SUMMARY_SELF_TEST_OK"]}],
            "sources": [{"name": "pcap-summary", "version": "1.0.0", "url": "https://github.com/luckylca/AutoCrackApp/tree/main/toolpacks/pcap-analysis", "sha256": sha256(root / "bin" / "pcap-summary")}],
        }
        write_outer(manifest, payload, Path(args.output_dir), "AutoCrackApp-rootfs-pcap-analysis-toolpack.zip", {"bin/pcap-summary"})

if __name__ == "__main__":
    main()
