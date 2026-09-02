#!/usr/bin/env python3
import argparse
import base64
import json
import os
import shutil
import subprocess
import sys
import time

VERSION = "0.1.0"
AUTHORITY = "com.luckylca.runtimeinspector.runtime"


class CliError(Exception):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code


def android_shell():
    override = os.environ.get("RUNTIME_INSPECTOR_ANDROID_SHELL")
    if override:
        return override.split()
    if shutil.which("android-shell"):
        return ["android-shell"]
    raise CliError("RUNTIME_UNAVAILABLE", "android-shell is not installed")


def provider_call(method, request=None):
    request = request or {}
    encoded = base64.b64encode(json.dumps(request, separators=(",", ":")).encode()).decode()
    command = android_shell() + ["content", "call", "--uri", f"content://{AUTHORITY}",
                                 "--method", method, "--extra", f"base64:s:{encoded}"]
    completed = subprocess.run(command, text=True, capture_output=True, timeout=15)
    if completed.returncode:
        raise CliError("RUNTIME_UNAVAILABLE", (completed.stderr or completed.stdout).strip())
    output = completed.stdout + completed.stderr
    marker = output.find('{"ok"')
    if marker < 0:
        raise CliError("INVALID_RESPONSE", output.strip() or "Provider returned no JSON")
    result, _ = json.JSONDecoder().raw_decode(output[marker:])
    if not result.get("ok", False):
        error = result.get("error", {})
        raise CliError(error.get("code", "RUNTIME_ERROR"), error.get("message", "Runtime request failed"))
    return result


def request(payload, timeout):
    submitted = provider_call("submit", payload)
    request_id = submitted["request_id"]
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        result = provider_call("result", {"request_id": request_id})
        if not result.get("pending", False):
            if result.get("missing"):
                raise CliError("REQUEST_MISSING", request_id)
            payload = result.get("result", {})
            if not payload.get("ok", False):
                error = payload.get("error", {})
                raise CliError(error.get("code", "INSPECT_FAILED"), error.get("message", "Inspection failed"))
            return payload
        time.sleep(0.25)
    raise CliError("INSPECT_TIMEOUT", f"Target process did not answer within {timeout:g}s")


def add_target(parser):
    parser.add_argument("--package", required=True)
    parser.add_argument("--process")
    parser.add_argument("--timeout", type=float, default=5.0)


def make_parser():
    parser = argparse.ArgumentParser(prog="runtime-inspector", description="Inspect live Android View hierarchies in authorized LSPosed-scoped apps")
    parser.add_argument("--version", action="version", version=f"runtime-inspector {VERSION}")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("status")
    clear = commands.add_parser("clear"); clear.add_argument("--package")

    windows = commands.add_parser("windows"); add_target(windows); windows.add_argument("--max-roots", type=int, default=64)
    tree = commands.add_parser("tree"); add_target(tree); tree.add_argument("--max-nodes", type=int, default=4000); tree.add_argument("--listeners", action="store_true")
    at = commands.add_parser("at"); add_target(at); at.add_argument("x", type=int); at.add_argument("y", type=int); at.add_argument("--max-nodes", type=int, default=4000); at.add_argument("--listeners", action="store_true"); at.add_argument("--include-hidden", action="store_true")
    action = commands.add_parser("action"); add_target(action); action.add_argument("--node-id"); action.add_argument("--x", type=int); action.add_argument("--y", type=int); action.add_argument("--action-json", required=True)
    return parser


def execute(args):
    if args.command == "status":
        return provider_call("status")
    if args.command == "clear":
        return provider_call("clear", {"package": args.package} if args.package else {})
    base = {"package": args.package, "process": args.process}
    if args.command == "windows":
        return request(base | {"kind": "windows", "max_roots": args.max_roots}, args.timeout)
    if args.command == "tree":
        return request(base | {"kind": "view_tree", "max_nodes": args.max_nodes, "include_listeners": args.listeners}, args.timeout)
    if args.command == "at":
        return request(base | {"kind": "view_at", "x": args.x, "y": args.y, "max_nodes": args.max_nodes, "include_listeners": args.listeners, "include_hidden": args.include_hidden}, args.timeout)
    if args.command == "action":
        try:
            action = json.loads(args.action_json)
        except json.JSONDecodeError as error:
            raise CliError("INVALID_ACTION_JSON", str(error)) from error
        payload = base | {"kind": "view_action", "action": action}
        if args.node_id: payload["node_id"] = args.node_id
        if args.x is not None: payload["x"] = args.x
        if args.y is not None: payload["y"] = args.y
        if not args.node_id and (args.x is None or args.y is None):
            raise CliError("TARGET_REQUIRED", "Use --node-id or both --x and --y")
        return request(payload, args.timeout)
    raise CliError("INVALID_COMMAND", args.command)


def main(argv=None):
    raw = list(sys.argv[1:] if argv is None else argv)
    json_output = "--json" in raw
    raw = [item for item in raw if item != "--json"]
    try:
        result = execute(make_parser().parse_args(raw))
        print(json.dumps(result, separators=(",", ":"), ensure_ascii=False) if json_output else json.dumps(result, indent=2, ensure_ascii=False))
        return 0
    except CliError as error:
        result = {"ok": False, "error": {"code": error.code, "message": str(error)}}
        print(json.dumps(result, separators=(",", ":"), ensure_ascii=False) if json_output else json.dumps(result, indent=2, ensure_ascii=False), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
