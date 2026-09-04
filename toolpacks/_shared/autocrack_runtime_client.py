#!/usr/bin/env python3
import base64
import json
import os
import shutil
import subprocess
import time

VERSION = "1.0.0"
AUTHORITY = "com.luckylca.autocrack.runtime"

RETRYABLE_READ_ONLY_KINDS = frozenset({
    "runtime.capabilities", "runtime.doctor", "runtime.process", "runtime.activities",
    "runtime.declared_activities", "runtime.classloaders", "runtime.class.search",
    "runtime.class.describe", "object.describe", "object.fields", "object.dump",
    "ui.windows", "ui.tree", "ui.at", "ui.find", "ui.props", "ui.parent",
    "ui.children", "ui.siblings", "ui.listeners", "ui.stack", "ui.image.result",
    "ui.compose.status", "ui.compose.tree",
    "memory.maps", "memory.modules", "memory.native.modules", "memory.read",
    "memory.native.probe", "memory.dladdr", "memory.module.dump",
    "memory.module.file_dump", "memory.elf.info", "memory.elf.symbols",
    "memory.elf.relocations", "memory.elf.dynamic", "memory.dex.list",
    "memory.dex.art_probe", "memory.dex.art_pointer_probe", "memory.dex.art_dump",
    "memory.dex.info", "memory.dex.apk_index", "memory.dex.strings",
    "memory.dex.classes", "memory.dex.fields", "memory.dex.methods",
    "memory.dex.class_data", "memory.dex.scan", "memory.dex.dump",
    "memory.assets.list", "memory.assets.pull", "memory.xml.pull",
    "memory.xml.block_probe", "memory.xml.binary", "memory.xml.axml_decode",
    "memory.xml.axml_text", "memory.apk.entries", "memory.apk.pull",
    "memory.capabilities", "webview.list", "webview.info",
    "webview.devtools_socket", "webview.eval.result", "control.secure.status",
    "control.secure.diagnose", "control.so.diagnose", "control.so.dlsym",
})


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

_TARGET_UID_CACHE = {}
_GREEZE_SUPPORTED = None
_GREEZE_THAW_REASON = "1000"
_GREEZE_THAW_INTERVAL_SECONDS = 0.75


def _target_uid(package_name):
    if not package_name:
        return None
    cached = _TARGET_UID_CACHE.get(package_name)
    if cached is not None:
        return cached
    try:
        completed = subprocess.run(
            android_shell() + ["--timeout-ms", "2500", "pm", "list", "packages", "-U", package_name],
            text=True, capture_output=True, timeout=3.0)
    except Exception:
        return None
    if completed.returncode != 0:
        return None
    wanted_prefix = f"package:{package_name} "
    for line in (completed.stdout + "\n" + completed.stderr).splitlines():
        stripped = line.strip()
        if not stripped.startswith(wanted_prefix):
            continue
        for token in stripped.split():
            if token.startswith("uid:") and token[4:].isdigit():
                uid = int(token[4:])
                _TARGET_UID_CACHE[package_name] = uid
                return uid
    return None


def best_effort_target_thaw(package_name):
    """Temporarily thaw a MIUI Greeze-frozen target; no-op on other Android builds."""
    global _GREEZE_SUPPORTED
    if _GREEZE_SUPPORTED is False:
        return False
    uid = _target_uid(package_name)
    if uid is None:
        return False
    try:
        completed = subprocess.run(
            android_shell() + [
                "--timeout-ms", "2000", "cmd", "greezer", "thuid", str(uid), _GREEZE_THAW_REASON
            ],
            text=True, capture_output=True, timeout=3.0)
    except Exception:
        return False
    if completed.returncode == 0:
        _GREEZE_SUPPORTED = True
        return True
    output = (completed.stdout + "\n" + completed.stderr).lower()
    if any(marker in output for marker in (
            "can't find service", "service greezer not found", "unknown service",
            "unknown command", "securityexception")):
        _GREEZE_SUPPORTED = False
    return False


def provider_call(method, request=None, timeout=15):
    request = request or {}
    transport_timeout = max(2.0, float(timeout))
    host_timeout_ms = max(100, int((transport_timeout - 1.0) * 1000))
    encoded = base64.b64encode(json.dumps(request, separators=(",", ":")).encode()).decode()
    cmd = android_shell() + ["--timeout-ms", str(host_timeout_ms), "content", "call", "--uri", f"content://{AUTHORITY}", "--method", method, "--extra", f"base64:s:{encoded}"]
    try:
        completed = subprocess.run(cmd, text=True, capture_output=True, timeout=transport_timeout)
    except subprocess.TimeoutExpired as exc:
        raise CliError("RUNTIME_TRANSPORT_TIMEOUT", f"Provider call {method} timed out after {timeout:g}s") from exc
    output = completed.stdout + completed.stderr
    if completed.returncode == 124:
        raise CliError("RUNTIME_TRANSPORT_TIMEOUT", f"Provider call {method} exceeded Android host timeout {host_timeout_ms}ms")
    marker = output.find('{"ok"')
    if marker < 0:
        if completed.returncode != 0:
            raise CliError("RUNTIME_UNAVAILABLE", output.strip() or "Provider call failed")
        raise CliError("INVALID_RUNTIME_RESPONSE", output.strip() or "Provider returned no JSON")
    try:
        result, _ = json.JSONDecoder().raw_decode(output[marker:])
    except json.JSONDecodeError as exc:
        raise CliError("INVALID_RUNTIME_RESPONSE", f"Provider returned malformed JSON: {exc}") from exc
    if not result.get("ok", False):
        error = result.get("error", {})
        raise CliError(error.get("code", "RUNTIME_ERROR"), error.get("message", "Runtime provider failed"))
    return result

def runtime_request(payload, timeout=10.0):
    kind = payload.get("kind", "")
    package_name = payload.get("package", "")
    read_only = kind in RETRYABLE_READ_ONLY_KINDS
    submit_attempts = 2 if read_only else 1
    submit_retries = 0
    target_thaws = 0
    last_target_thaw = 0.0

    def keep_target_thawed(force=False):
        nonlocal target_thaws, last_target_thaw
        now = time.monotonic()
        if not force and now - last_target_thaw < _GREEZE_THAW_INTERVAL_SECONDS:
            return
        last_target_thaw = now
        if best_effort_target_thaw(package_name):
            target_thaws += 1

    keep_target_thawed(force=True)
    submitted = None
    for submit_attempt in range(submit_attempts):
        try:
            submitted = provider_call("runtime_submit", payload)
            break
        except CliError as error:
            if error.code != "RUNTIME_TRANSPORT_TIMEOUT" or submit_attempt + 1 >= submit_attempts:
                raise
            submit_retries += 1
            keep_target_thawed(force=True)
            time.sleep(0.25)

    request_id = submitted["request_id"]
    deadline = time.monotonic() + float(timeout)
    last_transport_error = None
    while time.monotonic() < deadline:
        keep_target_thawed()
        remaining = deadline - time.monotonic()
        if remaining < 2.0:
            break
        poll_timeout = min(5.0, remaining)
        try:
            result = provider_call("runtime_result", {"request_id": request_id}, timeout=poll_timeout)
        except CliError as error:
            if error.code != "RUNTIME_TRANSPORT_TIMEOUT":
                raise
            last_transport_error = error
            if time.monotonic() < deadline:
                time.sleep(0.25)
            continue
        if not result.get("pending", False):
            if result.get("missing"):
                raise CliError("REQUEST_MISSING", request_id)
            response_payload = result.get("result", {})
            if not response_payload.get("ok", False):
                error = response_payload.get("error", {})
                raise CliError(error.get("code", "RUNTIME_FAILED"), error.get("message", "Runtime request failed"))
            if submit_retries:
                response_payload["client_submit_retries"] = submit_retries
            if target_thaws:
                response_payload["client_target_thaws"] = target_thaws
            return response_payload
        time.sleep(0.25)
    suffix = f"; last provider poll error: {last_transport_error}" if last_transport_error else ""
    raise CliError("RUNTIME_TIMEOUT", f"Target process did not answer within {timeout:g}s{suffix}")

def add_target(parser, package_required=True):
    parser.add_argument("--package", required=package_required)
    parser.add_argument("--process")
    parser.add_argument("--timeout", type=float, default=10.0)

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
