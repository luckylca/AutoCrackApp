#!/usr/bin/env python3
"""Validate the host-side contract for AutoCrack cross-tool workflows.

This checker is intentionally host-only. It does not install APKs, call Android
providers, run target-process requests, or touch LSPosed state. It verifies that
the maintained Toolpacks expose the CLI surfaces and manifest capability claims
needed by the Phase I integration flows.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path
from typing import Any


TOOLPACKS: dict[str, dict[str, Any]] = {
    "ui-inspect": {
        "bin": "toolpacks/ui-inspect/bin/ui-inspect",
        "manifest": "toolpacks/ui-inspect/dist/manifest.json",
        "help_contains": ["at", "listeners", "action", "image", "compose-tree"],
        "capabilities": ["ui.at", "ui.listeners", "ui.action", "ui.image", "object.describe"],
    },
    "runtime-inspect": {
        "bin": "toolpacks/runtime-inspect/bin/runtime-inspect",
        "manifest": "toolpacks/runtime-inspect/dist/manifest.json",
        "help_contains": ["classloaders", "class-search", "class-describe", "object"],
        "capabilities": ["runtime.classloaders", "runtime.class.search", "runtime.class.describe", "object.describe"],
    },
    "memory-dump": {
        "bin": "toolpacks/memory-dump/bin/memory-dump",
        "manifest": "toolpacks/memory-dump/dist/manifest.json",
        "help_contains": ["dex-list", "dex-art-probe", "dex-art-export", "xml-block-probe"],
        "capabilities": ["memory.dex.list", "memory.dex.art_probe", "memory.dex.art_export.open", "memory.xml.block_probe"],
    },
    "runtime-control": {
        "bin": "toolpacks/runtime-control/bin/runtime-control",
        "manifest": "toolpacks/runtime-control/dist/manifest.json",
        "help_contains": ["webview-list", "webview-debug", "webview-eval", "webview-devtools-sockets"],
        "capabilities": ["webview.list", "webview.debug", "webview.eval", "webview.devtools_socket"],
    },
    "simplehook": {
        "bin": "toolpacks/simplehook/bin/simplehook",
        "manifest": "toolpacks/simplehook/dist/manifest.json",
        "help_contains": ["rules", "inspect", "logs", "reload", "environment"],
        "capabilities": ["hook.reload", "hook.inspect"],
    },
}

FLOWS: dict[str, dict[str, list[str]]] = {
    "ui_at_to_runtime_object": {
        "tools": ["ui-inspect", "runtime-inspect"],
        "commands": ["ui-inspect:at", "runtime-inspect:object"],
        "capabilities": ["ui.at", "object.describe"],
    },
    "listener_to_simplehook_rule": {
        "tools": ["ui-inspect", "runtime-inspect", "simplehook"],
        "commands": ["ui-inspect:listeners", "runtime-inspect:class-describe", "simplehook:rules", "simplehook:reload", "simplehook:logs"],
        "capabilities": ["ui.listeners", "runtime.class.describe", "hook.reload"],
    },
    "classloader_to_memory_dex": {
        "tools": ["runtime-inspect", "memory-dump"],
        "commands": ["runtime-inspect:classloaders", "memory-dump:dex-list", "memory-dump:dex-art-probe", "memory-dump:dex-art-export"],
        "capabilities": ["runtime.classloaders", "memory.dex.list", "memory.dex.art_probe", "memory.dex.art_export.open"],
    },
    "webview_discovery_to_eval": {
        "tools": ["runtime-control"],
        "commands": ["runtime-control:webview-list", "runtime-control:webview-debug", "runtime-control:webview-eval", "runtime-control:webview-eval-result", "runtime-control:webview-devtools-sockets"],
        "capabilities": ["webview.list", "webview.debug", "webview.eval", "webview.eval.result", "webview.devtools_socket"],
    },
}


def repo_root() -> Path:
    return Path(__file__).resolve().parents[1]


def run_help(root: Path, rel: str) -> str:
    completed = subprocess.run(
        [str(root / rel), "--help"],
        cwd=root,
        text=True,
        capture_output=True,
        timeout=10,
    )
    if completed.returncode != 0:
        raise AssertionError((completed.stderr or completed.stdout).strip() or f"{rel} --help failed")
    return completed.stdout + completed.stderr


def load_manifest(root: Path, rel: str) -> dict[str, Any]:
    path = root / rel
    if not path.exists():
        raise AssertionError(f"missing manifest: {rel}; rebuild the Toolpack first")
    return json.loads(path.read_text(encoding="utf-8"))


def flatten_capabilities(manifest: dict[str, Any]) -> set[str]:
    requires = manifest.get("requires") or {}
    values = set(requires.get("capabilities") or [])
    values.update(requires.get("optionalCapabilities") or [])
    return values


def command_names(manifest: dict[str, Any]) -> set[str]:
    return {str(item.get("name")) for item in manifest.get("commands") or [] if item.get("name")}


def validate_toolpack(root: Path, name: str, spec: dict[str, Any]) -> dict[str, Any]:
    help_text = run_help(root, spec["bin"])
    manifest = load_manifest(root, spec["manifest"])
    missing_help = [item for item in spec["help_contains"] if item not in help_text]
    if missing_help:
        raise AssertionError(f"{name} help missing: {missing_help}")
    if manifest.get("schemaVersion") != 2:
        raise AssertionError(f"{name} manifest schemaVersion is {manifest.get('schemaVersion')}, expected 2")
    if manifest.get("payloadEntry") != "payload.zip":
        raise AssertionError(f"{name} payloadEntry is not payload.zip")
    if not manifest.get("payloadSha256") or int(manifest.get("payloadSizeBytes") or 0) <= 0:
        raise AssertionError(f"{name} payload hash/size missing")
    if not manifest.get("selfTests"):
        raise AssertionError(f"{name} selfTests missing")
    requires = manifest.get("requires") or {}
    if requires.get("runtime") != ">=1.0.0":
        raise AssertionError(f"{name} requires.runtime must be >=1.0.0")
    if "android-shell" not in set(requires.get("commands") or []):
        raise AssertionError(f"{name} requires.commands must include android-shell")
    manifest_caps = flatten_capabilities(manifest)
    missing_caps = [cap for cap in spec["capabilities"] if cap not in manifest_caps]
    if missing_caps:
        raise AssertionError(f"{name} manifest missing capabilities: {missing_caps}")
    if name not in command_names(manifest):
        raise AssertionError(f"{name} manifest commands must expose {name}")
    return {
        "ok": True,
        "schemaVersion": manifest["schemaVersion"],
        "payloadSha256": manifest["payloadSha256"],
        "command": name,
        "capabilityCount": len(manifest_caps),
    }


def validate_flows(help_cache: dict[str, str], cap_cache: dict[str, set[str]]) -> dict[str, Any]:
    flows: dict[str, Any] = {}
    for flow_name, flow in FLOWS.items():
        missing_commands: list[str] = []
        for entry in flow["commands"]:
            tool, command = entry.split(":", 1)
            if command not in help_cache[tool]:
                missing_commands.append(entry)
        available_caps = set().union(*(cap_cache[tool] for tool in flow["tools"]))
        missing_caps = [cap for cap in flow["capabilities"] if cap not in available_caps]
        ok = not missing_commands and not missing_caps
        flows[flow_name] = {
            "ok": ok,
            "tools": flow["tools"],
            "missingCommands": missing_commands,
            "missingCapabilities": missing_caps,
        }
    return flows


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Validate host-side AutoCrack cross-tool integration contracts")
    parser.add_argument("--json", action="store_true", help="emit compact JSON")
    args = parser.parse_args(argv)
    root = repo_root()
    result: dict[str, Any] = {"ok": True, "toolpacks": {}, "flows": {}}
    try:
        help_cache: dict[str, str] = {}
        cap_cache: dict[str, set[str]] = {}
        for name, spec in TOOLPACKS.items():
            help_cache[name] = run_help(root, spec["bin"])
            manifest = load_manifest(root, spec["manifest"])
            cap_cache[name] = flatten_capabilities(manifest)
            result["toolpacks"][name] = validate_toolpack(root, name, spec)
        result["flows"] = validate_flows(help_cache, cap_cache)
        result["ok"] = all(item["ok"] for item in result["flows"].values())
    except Exception as exc:  # noqa: BLE001 - CLI should always return structured failure.
        result = {"ok": False, "error": {"code": "CROSS_TOOL_CONTRACT_FAILED", "message": str(exc)}, **result}
    if args.json:
        print(json.dumps(result, ensure_ascii=False, separators=(",", ":")))
    else:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result.get("ok") else 1


if __name__ == "__main__":
    raise SystemExit(main())
