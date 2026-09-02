#!/usr/bin/env python3
import argparse
import base64
import json
import os
from pathlib import Path
import platform
import re
import shlex
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import time

VERSION = "1.0.0"
AUTHORITY = "com.luckylca.autocrack.runtime"
MODULE_PACKAGE = "com.luckylca.autocrack.runtime"
LSPOSED_DATABASE = "/data/adb/lspd/config/modules_config.db"
VALID_ACTIONS = {
    "record", "replace_return", "replace_argument", "before", "after", "skip_original",
    "field_read", "field_write", "field_record",
}
VALID_OPERATORS = {
    "eq", "ne", "gt", "gte", "lt", "lte", "contains", "starts_with", "ends_with",
    "is_null", "not_null",
}
PRIMITIVES = {"boolean", "byte", "short", "int", "long", "float", "double", "char", "void"}
BOXED = {
    "java.lang.Boolean": "boolean", "java.lang.Byte": "byte", "java.lang.Short": "short",
    "java.lang.Integer": "int", "java.lang.Long": "long", "java.lang.Float": "float",
    "java.lang.Double": "double", "java.lang.Character": "char",
}
CLASS_RE = re.compile(r"^[A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)+$")
ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$")


class CliError(Exception):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code


def response_error(code, message):
    return {"ok": False, "error": {"code": code, "message": message}}


def validate_type(name):
    if not isinstance(name, str) or not name:
        return False
    if name in PRIMITIVES or name == "String":
        return True
    return bool(re.match(r"^[A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)*(\[\])?$", name))


def coerce(value, declared_type):
    value_type = BOXED.get(declared_type, declared_type)
    if value is None:
        if declared_type in PRIMITIVES and declared_type != "void":
            raise CliError("NULL_FOR_PRIMITIVE", f"null is not valid for {declared_type}")
        return None
    if value_type == "boolean" and type(value) is not bool:
        raise CliError("TYPE_MISMATCH", f"Value is not compatible with {declared_type}")
    if value_type in {"byte", "short", "int", "long"}:
        if type(value) is not int:
            raise CliError("TYPE_MISMATCH", f"Value is not compatible with {declared_type}")
        bounds = {"byte": (-128, 127), "short": (-32768, 32767), "int": (-2147483648, 2147483647)}
        if value_type in bounds and not bounds[value_type][0] <= value <= bounds[value_type][1]:
            raise CliError("TYPE_MISMATCH", f"Value is outside {declared_type} range")
    if value_type in {"float", "double"} and (type(value) not in {int, float}):
        raise CliError("TYPE_MISMATCH", f"Value is not compatible with {declared_type}")
    if value_type == "char" and (not isinstance(value, str) or len(value) != 1):
        raise CliError("TYPE_MISMATCH", "char values must contain exactly one character")
    if value_type in {"String", "java.lang.String"} and not isinstance(value, str):
        raise CliError("TYPE_MISMATCH", f"Value is not compatible with {declared_type}")
    return value


def reject_unknown(value, allowed, label):
    unknown = sorted(set(value) - set(allowed))
    if unknown:
        raise CliError("INVALID_SCHEMA", f"Unknown {label} property: {unknown[0]}")


def validate_rule(rule):
    if not isinstance(rule, dict):
        raise CliError("INVALID_SCHEMA", "Rule must be a JSON object")
    reject_unknown(rule, {"schema_version", "id", "enabled", "package", "process",
                            "target", "action", "logging", "condition"}, "rule")
    if rule.get("schema_version") != 1:
        raise CliError("UNSUPPORTED_SCHEMA", "schema_version must be 1")
    if type(rule.get("enabled")) is not bool:
        raise CliError("INVALID_SCHEMA", "enabled must be a boolean")
    if not ID_RE.match(str(rule.get("id", ""))):
        raise CliError("INVALID_ID", "Invalid rule id")
    if not CLASS_RE.match(str(rule.get("package", ""))):
        raise CliError("INVALID_PACKAGE", "Invalid Android package name")
    if rule.get("process") is not None and (not isinstance(rule.get("process"), str) or not rule["process"]):
        raise CliError("INVALID_SCHEMA", "process must be null or a non-empty string")
    target = rule.get("target")
    action = rule.get("action")
    if not isinstance(target, dict) or not isinstance(action, dict):
        raise CliError("INVALID_SCHEMA", "target and action must be objects")
    reject_unknown(target, {"class", "method", "constructor", "parameters", "return_type", "field"}, "target")
    reject_unknown(action, {"type", "value", "argument_index"}, "action")
    if "constructor" in target and type(target["constructor"]) is not bool:
        raise CliError("INVALID_SCHEMA", "target.constructor must be a boolean")
    if "method" in target and not isinstance(target["method"], str):
        raise CliError("INVALID_SCHEMA", "target.method must be a string")
    if not CLASS_RE.match(str(target.get("class", ""))):
        raise CliError("INVALID_CLASS", "Invalid target class name")
    action_type = action.get("type")
    if action_type not in VALID_ACTIONS:
        raise CliError("INVALID_ACTION", f"Unsupported action type: {action_type}")
    field_action = str(action_type).startswith("field_")
    constructor = target.get("constructor", False)
    method = target.get("method", "")
    if field_action:
        if not target.get("field") or constructor or method:
            raise CliError("INVALID_TARGET", "Field actions require only target.class and target.field")
    elif constructor:
        if method not in {"", "<init>"} or action_type not in {"record", "before", "after"}:
            raise CliError("INVALID_TARGET", "Constructor supports record, before, and after")
    else:
        if method == "*":
            raise CliError("WILDCARD_TOO_BROAD", "A global method wildcard is not allowed")
        if not re.match(r"^[A-Za-z_$][A-Za-z0-9_$]*(\*)?$", str(method)):
            raise CliError("INVALID_METHOD", "Invalid method name")
    parameters = target.get("parameters")
    if (not isinstance(parameters, list) or len(parameters) > 64
            or not all(validate_type(item) for item in parameters)):
        raise CliError("INVALID_PARAMETERS", "target.parameters must contain valid exact type names")
    return_type = target.get("return_type")
    if not field_action and not constructor and not validate_type(return_type):
        raise CliError("INVALID_RETURN_TYPE", "A valid return_type is required")
    if action_type == "replace_argument":
        index = action.get("argument_index")
        if type(index) is not int or index < 0 or index >= len(parameters):
            raise CliError("INVALID_ARGUMENT_INDEX", "argument_index is out of range")
        if "value" not in action:
            raise CliError("MISSING_VALUE", "replace_argument requires value")
        coerce(action["value"], parameters[index])
    if action_type in {"replace_return", "skip_original"}:
        if "value" not in action:
            raise CliError("MISSING_VALUE", f"{action_type} requires value")
        if return_type == "void":
            raise CliError("INVALID_RETURN_TYPE", f"{action_type} cannot target void")
        coerce(action["value"], return_type)
    if action_type == "field_write" and "value" not in action:
        raise CliError("MISSING_VALUE", "field_write requires value")
    condition = rule.get("condition")
    if condition is not None:
        reject_unknown(condition, {"source", "index", "operator", "value"}, "condition")
        if not isinstance(condition, dict) or condition.get("source") not in {"argument", "return_value", "field"}:
            raise CliError("INVALID_CONDITION", "Unsupported condition source")
        if condition.get("operator") not in VALID_OPERATORS:
            raise CliError("INVALID_CONDITION", "Unsupported condition operator")
        if condition["source"] == "argument":
            index = condition.get("index")
            if type(index) is not int or index < 0 or index >= len(parameters):
                raise CliError("INVALID_CONDITION", "Condition argument index is out of range")
        if condition["operator"] not in {"is_null", "not_null"} and "value" not in condition:
            raise CliError("INVALID_CONDITION", "Condition operator requires value")
        if field_action and condition["source"] != "field":
            raise CliError("INVALID_CONDITION", "Field actions require a field condition source")
        if action_type in {"replace_argument", "skip_original", "before"} and condition["source"] == "return_value":
            raise CliError("INVALID_CONDITION", f"{action_type} cannot use a return_value condition")
    logging = rule.get("logging")
    if logging is not None:
        if not isinstance(logging, dict):
            raise CliError("INVALID_SCHEMA", "logging must be an object")
        reject_unknown(logging, {"enabled", "arguments", "return_value", "stack_trace"}, "logging")
        for key, value in logging.items():
            if type(value) is not bool:
                raise CliError("INVALID_SCHEMA", f"logging.{key} must be a boolean")
    return rule


class FileBackend:
    def __init__(self, root):
        self.root = Path(root)
        self.rules_dir = self.root / "rules"
        self.rules_dir.mkdir(parents=True, exist_ok=True)

    def _files(self):
        return sorted(self.rules_dir.glob("*.json"))

    def _load(self, rule_id):
        path = self.rules_dir / f"{rule_id}.json"
        if not path.is_file():
            raise CliError("RULE_NOT_FOUND", f"Rule not found: {rule_id}")
        return json.loads(path.read_text(encoding="utf-8"))

    def call(self, method, request):
        if method == "status":
            rules = [json.loads(path.read_text(encoding="utf-8")) for path in self._files()]
            return {"ok": True, "version": VERSION, "runtime": {
                        "available": False, "module_enabled": None,
                        "module_enabled_source": "runtime_unavailable",
                        "runtime_attached": False, "heartbeat_recent": False,
                        "heartbeat_max_age_ms": 5000, "active_process_count": 0},
                    "rules": {"total": len(rules), "active": sum(item.get("enabled", True) for item in rules)}, "processes": []}
        if method == "rules_list":
            return {"ok": True, "rules": [self._with_state(json.loads(path.read_text(encoding="utf-8"))) for path in self._files()], "generation": self._generation()}
        if method == "rules_show":
            return {"ok": True, "rule": self._load(request["id"])}
        if method in {"rules_add", "rules_update"}:
            rule = validate_rule(request["rule"])
            path = self.rules_dir / f"{rule['id']}.json"
            exists = path.exists()
            if method == "rules_add" and exists:
                raise CliError("RULE_EXISTS", f"Rule already exists: {rule['id']}")
            path.write_text(json.dumps(rule, indent=2) + "\n", encoding="utf-8")
            self._bump()
            return {"ok": True, "rule": rule, "created": not exists, "requires_restart": False}
        if method in {"rules_enable", "rules_disable"}:
            rule = self._load(request["id"])
            rule["enabled"] = method == "rules_enable"
            return self.call("rules_update", {"rule": rule})
        if method == "rules_remove":
            path = self.rules_dir / f"{request['id']}.json"
            if not path.exists():
                raise CliError("RULE_NOT_FOUND", f"Rule not found: {request['id']}")
            path.unlink()
            self._bump()
            return {"ok": True, "removed": request["id"], "requires_restart": False}
        if method == "reload":
            return {"ok": True, "generation": self._bump(), "requires_restart": False}
        if method == "logs":
            selected = []
            log_path = self.root / "simplehook.jsonl"
            if log_path.is_file():
                for line in log_path.read_text(encoding="utf-8").splitlines():
                    item = json.loads(line)
                    if request.get("rule_id") and item.get("rule_id") != request["rule_id"]:
                        continue
                    if request.get("package") and item.get("package") != request["package"]:
                        continue
                    selected.append(item)
            return {"ok": True, "logs": selected[-request.get("limit", 500):]}
        if method == "limits":
            return {"ok": True, "limits": {"max_rules": 256, "max_hooked_methods": 512,
                    "max_wildcard_expansion": 64, "max_logs_per_second": 100,
                    "max_log_entry_bytes": 32768, "max_stack_trace_chars": 16384,
                    "max_log_file_bytes": 4194304, "max_log_files": 4}}
        if method.startswith("inspect_"):
            raise CliError("RUNTIME_UNAVAILABLE", "Inspect requires the Android runtime module")
        raise CliError("UNKNOWN_METHOD", f"Unsupported backend method: {method}")

    def _generation(self):
        path = self.root / "generation"
        return int(path.read_text().strip()) if path.exists() else 0

    def _bump(self):
        value = self._generation() + 1
        (self.root / "generation").write_text(f"{value}\n", encoding="ascii")
        return value

    @staticmethod
    def _with_state(rule):
        copy = dict(rule)
        copy["runtime"] = {"state": "WAITING_FOR_PROCESS" if rule.get("enabled", True) else "DISABLED"}
        return copy


class ProviderBackend:
    def call(self, method, request):
        if not provider_command():
            raise CliError("RUNTIME_UNAVAILABLE", "android-shell is not installed or configured")
        encoded = base64.b64encode(json.dumps(request, separators=(",", ":")).encode()).decode()
        command = provider_command() + ["content", "call", "--uri", f"content://{AUTHORITY}",
                                        "--method", method, "--extra", f"base64:s:{encoded}"]
        completed = subprocess.run(command, text=True, capture_output=True, timeout=15)
        if completed.returncode != 0:
            raise CliError("RUNTIME_UNAVAILABLE", (completed.stderr or completed.stdout).strip() or "Provider call failed")
        output = completed.stdout + completed.stderr
        marker = output.find("{\"ok\"")
        if marker < 0:
            raise CliError("INVALID_RUNTIME_RESPONSE", output.strip() or "Provider returned no JSON")
        try:
            result, _ = json.JSONDecoder().raw_decode(output[marker:])
        except json.JSONDecodeError as error:
            raise CliError("INVALID_RUNTIME_RESPONSE", str(error)) from error
        return result


def provider_command():
    override = os.environ.get("SIMPLEHOOK_ANDROID_SHELL")
    if override:
        return override.split()
    if shutil.which("android-shell"):
        return ["android-shell"]
    return []


def backend():
    root = os.environ.get("SIMPLEHOOK_HOME")
    return FileBackend(root) if root else ProviderBackend()


def read_rule(path):
    try:
        text = sys.stdin.read() if path == "-" else Path(path).read_text(encoding="utf-8")
        return validate_rule(json.loads(text))
    except FileNotFoundError as error:
        raise CliError("FILE_NOT_FOUND", str(error)) from error
    except json.JSONDecodeError as error:
        raise CliError("INVALID_JSON", f"{error.msg} at line {error.lineno}, column {error.colno}") from error


def call_checked(store, method, request=None):
    result = store.call(method, request or {})
    if not result.get("ok", False):
        error = result.get("error", {})
        raise CliError(error.get("code", "RUNTIME_ERROR"), error.get("message", "Runtime request failed"))
    return result


def android_value(command):
    base = provider_command()
    if not base:
        return None
    completed = subprocess.run(base + command, text=True, capture_output=True, timeout=8)
    return completed.stdout.strip() if completed.returncode == 0 else None


def read_lsposed_module_status(database_path):
    """Read LSPosed configuration from a copied database without changing the live database."""
    try:
        connection = sqlite3.connect(database_path)
        connection.row_factory = sqlite3.Row
        try:
            integrity = connection.execute("PRAGMA quick_check(1)").fetchone()[0]
            if integrity != "ok":
                raise sqlite3.DatabaseError(f"quick_check failed: {integrity}")
            tables = {row[0] for row in connection.execute(
                "SELECT name FROM sqlite_master WHERE type='table'"
            )}
            required = {"modules", "modules_state", "scope"}
            if not required.issubset(tables):
                raise sqlite3.DatabaseError("unsupported LSPosed database schema")
            installed = connection.execute(
                "SELECT 1 FROM modules WHERE module_pkg_name=? LIMIT 1", (MODULE_PACKAGE,)
            ).fetchone() is not None
            state = connection.execute(
                "SELECT enabled FROM modules_state WHERE module_pkg_name=? AND user_id=0 LIMIT 1",
                (MODULE_PACKAGE,),
            ).fetchone()
            scopes = [row[0] for row in connection.execute(
                "SELECT app_pkg_name FROM scope WHERE module_pkg_name=? AND user_id=0 ORDER BY app_pkg_name",
                (MODULE_PACKAGE,),
            )]
            return {
                "package": MODULE_PACKAGE,
                "installed": installed,
                "enabled": bool(state[0]) if state is not None else False,
                "scope_count": len(scopes),
                "scope_packages": scopes,
                "source": "lsposed_database",
            }
        finally:
            connection.close()
    except (OSError, sqlite3.Error) as error:
        return {
            "package": MODULE_PACKAGE,
            "installed": None,
            "enabled": None,
            "scope_count": None,
            "scope_packages": [],
            "source": "unavailable",
            "error": {"code": "LSPOSED_STATUS_UNAVAILABLE", "message": str(error)},
        }


def lsposed_module_status():
    base = provider_command()
    workspace = Path("/workspace")
    if not base or not workspace.is_dir():
        return {
            "package": MODULE_PACKAGE, "installed": None, "enabled": None,
            "scope_count": None, "scope_packages": [], "source": "unavailable",
            "error": {"code": "LSPOSED_STATUS_UNAVAILABLE",
                      "message": "Android root bridge or shared workspace is unavailable"},
        }

    source_files = [LSPOSED_DATABASE, LSPOSED_DATABASE + "-wal", LSPOSED_DATABASE + "-shm"]
    hash_script = "; ".join(
        f'if test -e {shlex.quote(item)}; then sha256sum {shlex.quote(item)}; '
        f'else echo MISSING:{shlex.quote(item)}; fi' for item in source_files
    )
    last_error = "LSPosed database changed while it was being copied"
    for _ in range(3):
        with tempfile.TemporaryDirectory(prefix=".simplehook-lsposed-", dir=workspace) as temporary:
            destination = "/workspace/" + Path(temporary).name
            copy_parts = [
                f'cp {shlex.quote(LSPOSED_DATABASE)} {shlex.quote(destination + "/modules_config.db")}'
            ]
            for suffix in ("-wal", "-shm"):
                source = LSPOSED_DATABASE + suffix
                target = destination + "/modules_config.db" + suffix
                copy_parts.append(
                    f'if test -e {shlex.quote(source)}; then cp {shlex.quote(source)} {shlex.quote(target)}; fi'
                )
            before = android_value(["sh", "-c", hash_script])
            copied = subprocess.run(base + ["sh", "-c", "; ".join(copy_parts)],
                                    text=True, capture_output=True, timeout=8)
            after = android_value(["sh", "-c", hash_script])
            if copied.returncode != 0:
                last_error = (copied.stderr or copied.stdout).strip() or "Unable to copy LSPosed database"
                continue
            if before is None or before != after:
                continue
            result = read_lsposed_module_status(Path(temporary) / "modules_config.db")
            if result["source"] == "lsposed_database":
                return result
            last_error = result.get("error", {}).get("message", last_error)
    return {
        "package": MODULE_PACKAGE, "installed": None, "enabled": None,
        "scope_count": None, "scope_packages": [], "source": "unavailable",
        "error": {"code": "LSPOSED_STATUS_UNAVAILABLE", "message": last_error},
    }


def status_result(store):
    result = call_checked(store, "status")
    module = lsposed_module_status()
    runtime = result.setdefault("runtime", {})
    runtime.setdefault("runtime_attached", False)
    runtime.setdefault("heartbeat_recent", runtime["runtime_attached"])
    runtime["module_enabled"] = module["enabled"]
    runtime["module_enabled_source"] = module["source"]
    runtime["module_scoped"] = module["scope_count"] > 0 if module["scope_count"] is not None else None
    runtime["scope_packages"] = module["scope_packages"]
    result["module"] = module
    return result


def environment_result(store):
    sdk = android_value(["getprop", "ro.build.version.sdk"])
    release = android_value(["getprop", "ro.build.version.release"])
    abi = android_value(["getprop", "ro.product.cpu.abi"])
    identity = android_value(["id"])
    selinux = android_value(["getenforce"])
    framework = android_value(["sh", "-c", "test -e /system/framework/XposedBridge.jar -o -d /data/adb/lspd && echo yes || echo no"])
    status = status_result(store)
    runtime = status.get("runtime", {})
    module = status.get("module", {})
    return {"ok": True, "android": {"version": release, "api_level": int(sdk) if sdk and sdk.isdigit() else None,
            "abi": abi, "root": bool(identity and "uid=0" in identity), "selinux": selinux},
            "xposed_compatible_runtime": framework == "yes",
            "module_enabled": module.get("enabled"),
            "module_scoped": runtime.get("module_scoped"),
            "scope_packages": module.get("scope_packages", []),
            "runtime_attached": bool(runtime.get("runtime_attached")),
            "heartbeat_recent": bool(runtime.get("heartbeat_recent")),
            "runtime_available": bool(runtime.get("available")),
            "module": module}


def resolve_package(store, class_name, explicit):
    if explicit:
        return explicit
    rules = call_checked(store, "rules_list").get("rules", [])
    packages = sorted({item.get("package") for item in rules if item.get("target", {}).get("class") == class_name})
    if len(packages) != 1:
        raise CliError("PACKAGE_REQUIRED", "Use --package when the class is not associated with exactly one rule package")
    return packages[0]


def inspect(store, kind, class_name, package_name, timeout):
    package_name = resolve_package(store, class_name, package_name)
    submitted = call_checked(store, "inspect_submit", {"kind": kind, "class": class_name, "package": package_name})
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        result = call_checked(store, "inspect_result", {"request_id": submitted["request_id"]})
        if not result.get("pending"):
            payload = result["result"]
            if not payload.get("ok"):
                error = payload.get("error", {})
                raise CliError(error.get("code", "INSPECT_FAILED"), error.get("message", "Inspect failed"))
            return payload
        time.sleep(0.25)
    raise CliError("INSPECT_TIMEOUT", "Target process did not answer the inspect request")


def make_parser():
    parser = argparse.ArgumentParser(prog="simplehook", description="Android Java method debugging rules for LSPosed/Xposed test environments")
    parser.add_argument("--version", action="version", version=f"simplehook {VERSION}")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("status", help="Show runtime and rule status")
    commands.add_parser("environment", help="Detect Android, root, SELinux, and Xposed runtime")
    commands.add_parser("doctor", help="Run non-mutating environment diagnostics")
    commands.add_parser("apply", help="Apply the current persistent rules")
    commands.add_parser("reload", help="Reload rules in active target processes")

    rules = commands.add_parser("rules", help="Manage persistent rules").add_subparsers(dest="rules_command", required=True)
    rules.add_parser("list", help="List rules and runtime states")
    show = rules.add_parser("show", help="Show one rule"); show.add_argument("id")
    add = rules.add_parser("add", help="Add a rule from FILE or stdin"); add.add_argument("file", nargs="?", default="-"); add.add_argument("--dry-run", action="store_true")
    update = rules.add_parser("update", help="Replace a rule by ID"); update.add_argument("id"); update.add_argument("file", nargs="?", default="-"); update.add_argument("--dry-run", action="store_true")
    for name in ("enable", "disable", "remove"):
        item = rules.add_parser(name, help=f"{name.capitalize()} a rule"); item.add_argument("id")
    validate = rules.add_parser("validate", help="Validate a rule without applying it"); validate.add_argument("file")

    logs = commands.add_parser("logs", help="Read structured runtime logs")
    logs.add_argument("--follow", action="store_true"); logs.add_argument("--rule"); logs.add_argument("--package"); logs.add_argument("--limit", type=int, default=500)

    inspect_parser = commands.add_parser("inspect", help="Inspect a loaded Java class").add_subparsers(dest="inspect_command", required=True)
    for name in ("class", "methods", "fields"):
        item = inspect_parser.add_parser(name); item.add_argument("class_name"); item.add_argument("--package"); item.add_argument("--timeout", type=float, default=5.0)
    return parser


def execute(args, store):
    if args.command == "status":
        return status_result(store)
    if args.command == "environment":
        return environment_result(store)
    if args.command == "doctor":
        environment = environment_result(store)
        checks = [{"id": "android", "ok": environment["android"]["api_level"] is not None, "required": True},
                  {"id": "root", "ok": environment["android"]["root"], "required": True},
                  {"id": "xposed_runtime", "ok": environment["xposed_compatible_runtime"], "required": True},
                  {"id": "module_enabled", "ok": environment["module_enabled"] is True, "required": True,
                   "source": environment["module"].get("source")},
                  {"id": "runtime_attached", "ok": environment["runtime_attached"], "required": False,
                   "detail": "No recent heartbeat is expected when no scoped target process is active"}]
        required_ok = all(item["ok"] for item in checks if item["required"])
        return {"ok": required_ok, "checks": checks, "environment": environment,
                "guidance": "A missing heartbeat never means the LSPosed module is disabled. SimpleHook diagnostics are read-only and never change LSPosed configuration."}
    if args.command in {"apply", "reload"}:
        return call_checked(store, "reload")
    if args.command == "rules":
        command = args.rules_command
        if command == "list": return call_checked(store, "rules_list")
        if command == "show": return call_checked(store, "rules_show", {"id": args.id})
        if command == "validate": return {"ok": True, "valid": True, "rule": read_rule(args.file)}
        if command in {"add", "update"}:
            rule = read_rule(args.file)
            if command == "update" and rule["id"] != args.id:
                raise CliError("ID_MISMATCH", "Rule document id must match the update ID")
            if args.dry_run: return {"ok": True, "valid": True, "dry_run": True, "rule": rule}
            return call_checked(store, "rules_" + command, {"rule": rule})
        return call_checked(store, "rules_" + command, {"id": args.id})
    if args.command == "logs":
        request = {"rule_id": args.rule, "package": args.package, "limit": args.limit}
        if not args.follow: return call_checked(store, "logs", request)
        seen = set()
        while True:
            result = call_checked(store, "logs", request)
            for item in result.get("logs", []):
                key = json.dumps(item, sort_keys=True)
                if key not in seen:
                    seen.add(key); print(json.dumps(item, separators=(",", ":")), flush=True)
            time.sleep(1)
    if args.command == "inspect":
        return inspect(store, args.inspect_command, args.class_name, args.package, args.timeout)
    raise CliError("INVALID_COMMAND", "No command selected")


def main(argv=None):
    raw = list(sys.argv[1:] if argv is None else argv)
    json_output = "--json" in raw
    raw = [item for item in raw if item != "--json"]
    try:
        args = make_parser().parse_args(raw)
        result = execute(args, backend())
        if args.command == "logs" and not json_output:
            for item in result.get("logs", []): print(json.dumps(item, separators=(",", ":")))
        elif json_output:
            print(json.dumps(result, separators=(",", ":")))
        else:
            print(json.dumps(result, indent=2))
        if not result.get("ok", False): return 1
        return 0
    except CliError as error:
        result = response_error(error.code, str(error))
        print(json.dumps(result, separators=(",", ":")) if json_output else json.dumps(result, indent=2), file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
