#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import re
import stat
import zipfile
from pathlib import Path
from urllib.parse import urlparse

FIXED_TIME = (1980, 1, 1, 0, 0, 0)
SAFE_SOURCE = re.compile(r"[^A-Za-z0-9._-]+")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def source_id(name: str) -> str:
    value = SAFE_SOURCE.sub("-", name).strip("-").lower()
    return value[:110] or "source"


def parse_pip_report(path: Path) -> list[dict]:
    report = json.loads(path.read_text(encoding="utf-8"))
    result: list[dict] = []
    seen: set[str] = set()
    for item in report.get("install", []):
        metadata = item.get("metadata") or {}
        download = item.get("download_info") or {}
        archive = download.get("archive_info") or {}
        url = download.get("url") or ""
        hashes = archive.get("hashes") or {}
        digest = hashes.get("sha256")
        if not digest:
            raw = archive.get("hash") or ""
            if raw.startswith("sha256="):
                digest = raw.split("=", 1)[1]
        name = metadata.get("name") or Path(urlparse(url).path).name or "pip-artifact"
        version = str(metadata.get("version") or "unknown")
        if not (
            url.startswith("https://")
            and digest
            and re.fullmatch(r"[0-9a-fA-F]{64}", digest)
        ):
            continue
        source_name = source_id("pip-" + name)
        base = source_name
        suffix = 2
        while source_name in seen:
            source_name = f"{base}-{suffix}"
            suffix += 1
        seen.add(source_name)
        result.append(
            {
                "name": source_name,
                "version": version,
                "url": url,
                "sha256": digest.lower(),
            }
        )
    return result


def deterministic_zip(root: Path, output: Path, executable_paths: set[str]) -> None:
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED) as archive:
        files = sorted(
            (path for path in root.rglob("*") if path.is_file()),
            key=lambda path: path.relative_to(root).as_posix(),
        )
        for path in files:
            relative = path.relative_to(root).as_posix()
            info = zipfile.ZipInfo(relative, FIXED_TIME)
            info.create_system = 3
            executable = (
                relative in executable_paths
                or relative.startswith("bin/")
                or relative.startswith("libexec/")
            )
            mode = 0o755 if executable else 0o644
            info.external_attr = (stat.S_IFREG | mode) << 16
            info.compress_type = zipfile.ZIP_STORED
            archive.writestr(info, path.read_bytes())


def write_outer(manifest_path: Path, payload_zip: Path, output: Path) -> None:
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED) as archive:
        for path in (manifest_path, payload_zip):
            info = zipfile.ZipInfo(path.name, FIXED_TIME)
            info.create_system = 3
            info.external_attr = (stat.S_IFREG | 0o644) << 16
            info.compress_type = zipfile.ZIP_STORED
            archive.writestr(info, path.read_bytes())


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Build a deterministic full-upstream AutoCrack Toolpack"
    )
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--payload-root", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--pip-report", action="append", default=[], type=Path)
    args = parser.parse_args()

    cfg = json.loads(args.config.read_text(encoding="utf-8"))
    for key in ("id", "title", "version", "architecture", "outputName"):
        if not cfg.get(key):
            raise SystemExit(f"missing config field: {key}")
    if not args.payload_root.is_dir():
        raise SystemExit(f"payload root missing: {args.payload_root}")
    if not (args.payload_root / "SKILL.md").is_file():
        raise SystemExit("full-upstream Toolpack must contain SKILL.md")

    args.output_dir.mkdir(parents=True, exist_ok=True)
    payload_zip = args.output_dir / "payload.zip"
    deterministic_zip(
        args.payload_root,
        payload_zip,
        set(cfg.get("executablePaths", [])),
    )

    sources = list(cfg.get("sources", []))
    for report in args.pip_report:
        sources.extend(parse_pip_report(report))
    unique_sources: dict[str, dict] = {}
    for source in sources:
        name = source["name"]
        if name in unique_sources and unique_sources[name] != source:
            raise SystemExit(f"duplicate source id with different values: {name}")
        unique_sources[name] = source
    sources = [unique_sources[name] for name in sorted(unique_sources)]
    if not sources:
        raise SystemExit("at least one pinned HTTPS source is required")

    commands = list(cfg.get("commands", []))
    if cfg.get("commandsFromBin", False):
        existing = {command["name"] for command in commands}
        bin_dir = args.payload_root / "bin"
        if bin_dir.is_dir():
            for path in sorted(item for item in bin_dir.iterdir() if item.is_file()):
                if path.name in existing:
                    continue
                commands.append(
                    {
                        "name": path.name,
                        "relativePath": f"bin/{path.name}",
                        "description": f"Upstream {path.name} command.",
                    }
                )
                existing.add(path.name)

    required_paths = list(cfg.get("requiredPaths", []))
    if cfg.get("requireAllBinCommands", False):
        for command in commands:
            relative = command["relativePath"]
            if relative not in required_paths:
                required_paths.append(relative)
    if "SKILL.md" not in required_paths:
        required_paths.append("SKILL.md")

    manifest = {
        "schemaVersion": int(cfg.get("schemaVersion", 2)),
        "id": cfg["id"],
        "title": cfg["title"],
        "version": cfg["version"],
        "description": cfg.get("description", ""),
        "architecture": cfg["architecture"],
        "payloadEntry": "payload.zip",
        "payloadSha256": sha256(payload_zip),
        "payloadSizeBytes": payload_zip.stat().st_size,
        "requiredPaths": required_paths,
        "commands": commands,
        "selfTests": cfg["selfTests"],
        "sources": sources,
    }
    if manifest["schemaVersion"] >= 2:
        manifest["requires"] = cfg.get(
            "requires",
            {
                "runtime": None,
                "capabilities": [],
                "commands": [],
                "optionalCapabilities": [],
            },
        )

    manifest_path = args.output_dir / "manifest.json"
    manifest_path.write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    (args.output_dir / "payload.sha256").write_text(
        manifest["payloadSha256"] + "\n",
        encoding="utf-8",
    )
    (args.output_dir / "payload.size").write_text(
        str(manifest["payloadSizeBytes"]) + "\n",
        encoding="utf-8",
    )

    output = args.output_dir / cfg["outputName"]
    write_outer(manifest_path, payload_zip, output)
    (args.output_dir / f"{output.name}.sha256").write_text(
        f"{sha256(output)}  {output.name}\n",
        encoding="utf-8",
    )
    print(f"TOOLPACK={output}")
    print(f"PAYLOAD_SHA256={manifest['payloadSha256']}")
    print(f"PAYLOAD_SIZE={manifest['payloadSizeBytes']}")
    print(f"TOOLPACK_SHA256={sha256(output)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
