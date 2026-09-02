#!/usr/bin/env python3
import base64
import json
import os
import shutil
import subprocess
import time

VERSION = "1.0.0"
AUTHORITY = "com.luckylca.autocrack.runtime"

class CliError(Exception):
    def __init__(self, code, message):
        super().__init__(str(message))
        self.code = code

def android_shell():
    override = os.environ.get("AUTOCRACK_ANDROID_SHELL")
    if override:
        return override.split()
    if shutil.which("android-shell"):
        return ["android-shell"]
    raise CliError("RUNTIME_UNAVAILABLE", "android-shell is not installed or not on PATH")

def provider_call(method, request=None, timeout=15):
    request = request or {}
    encoded = base64.b64encode(json.dumps(request, separators=(",", ":")).encode()).decode()
    cmd = android_shell() + ["content", "call", "--uri", f"content://{AUTHORITY}", "--method", method, "--extra", f"base64:s:{encoded}"]
    completed = subprocess.run(cmd, text=True, capture_output=True, timeout=timeout)
    if completed.returncode != 0:
        raise CliError("RUNTIME_UNAVAILABLE", (completed.stderr or completed.stdout).strip() or "Provider call failed")
    output = completed.stdout + completed.stderr
    marker = output.find('{"ok"')
    if marker < 0:
        raise CliError("INVALID_RUNTIME_RESPONSE", output.strip() or "Provider returned no JSON")
    try:
        result, _ = json.JSONDecoder().raw_decode(output[marker:])
    except json.JSONDecodeError as exc:
        raise CliError("INVALID_RUNTIME_RESPONSE", f"Provider returned malformed JSON: {exc}") from exc
    if not result.get("ok", False):
        error = result.get("error", {})
        raise CliError(error.get("code", "RUNTIME_ERROR"), error.get("message", "Runtime provider failed"))
    return result

def runtime_request(payload, timeout=5.0):
    submitted = provider_call("runtime_submit", payload)
    request_id = submitted["request_id"]
    deadline = time.monotonic() + float(timeout)
    while time.monotonic() < deadline:
        result = provider_call("runtime_result", {"request_id": request_id})
        if not result.get("pending", False):
            if result.get("missing"):
                raise CliError("REQUEST_MISSING", request_id)
            payload = result.get("result", {})
            if not payload.get("ok", False):
                error = payload.get("error", {})
                raise CliError(error.get("code", "RUNTIME_FAILED"), error.get("message", "Runtime request failed"))
            return payload
        time.sleep(0.25)
    raise CliError("RUNTIME_TIMEOUT", f"Target process did not answer within {timeout:g}s")

def add_target(parser, package_required=True):
    parser.add_argument("--package", required=package_required)
    parser.add_argument("--process")
    parser.add_argument("--timeout", type=float, default=5.0)

def target_payload(args, kind, **extra):
    payload = {"kind": kind, "package": args.package, "process": args.process}
    payload.update({k: v for k, v in extra.items() if v is not None})
    return payload

def parse_json_arg(text, code="INVALID_JSON"):
    try:
        return json.loads(text)
    except json.JSONDecodeError as exc:
        raise CliError(code, str(exc)) from exc

def emit_result(result, json_output):
    print(json.dumps(result, ensure_ascii=False, separators=(",", ":")) if json_output else json.dumps(result, ensure_ascii=False, indent=2))

def emit_error(error, json_output):
    result = {"ok": False, "error": {"code": getattr(error, "code", "CLI_ERROR"), "message": str(error)}}
    print(json.dumps(result, ensure_ascii=False, separators=(",", ":")) if json_output else json.dumps(result, ensure_ascii=False, indent=2), file=os.sys.stderr)
