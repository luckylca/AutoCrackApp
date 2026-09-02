#!/usr/bin/env python3
"""Run the SimpleHook feature matrix on an owned LSPosed test device."""

import argparse
import base64
import json
import subprocess
import sys
import time

RUNTIME_AUTHORITY = "com.luckylca.autocrack.runtime"
TEST_AUTHORITY = "com.luckylca.simplehook.testapp.control"
TEST_PACKAGE = "com.luckylca.simplehook.testapp"
TARGET_CLASS = "com.luckylca.simplehook.testapp.HookTargets"
PREFIX = "device_simplehook_"


class DeviceTest:
    def __init__(self, serial):
        self.adb = ["adb", "-s", serial]
        self.results = {}

    def shell(self, command, check=True):
        completed = subprocess.run(self.adb + ["shell", command], text=True,
                                   capture_output=True, timeout=30)
        if check and completed.returncode:
            raise RuntimeError((completed.stderr or completed.stdout).strip())
        return completed.stdout + completed.stderr

    def root_shell(self, command, check=True):
        completed = subprocess.run(self.adb + ["shell", "su", "-c", command], text=True,
                                   capture_output=True, timeout=30)
        if check and completed.returncode:
            raise RuntimeError((completed.stderr or completed.stdout).strip())
        return completed.stdout + completed.stderr

    @staticmethod
    def parse_bundle(output):
        marker = output.find("{\"ok\"")
        if marker < 0:
            raise RuntimeError(f"No JSON bundle in: {output.strip()}")
        return json.JSONDecoder().raw_decode(output[marker:])[0]

    def runtime(self, method, request=None):
        request = request or {}
        encoded = base64.b64encode(json.dumps(request, separators=(",", ":")).encode()).decode()
        output = self.shell(f"content call --uri content://{RUNTIME_AUTHORITY} --method {method} "
                            f"--extra base64:s:{encoded}")
        return self.parse_bundle(output)

    def target(self, operation):
        output = self.shell(f"content call --uri content://{TEST_AUTHORITY} --method invoke --arg {operation}")
        return self.parse_bundle(output)

    def stop_target(self):
        self.shell(f"am force-stop {TEST_PACKAGE}")

    def rule(self, name, method, action, parameters=None, return_type="int", **target_extra):
        target = {"class": TARGET_CLASS, "method": method, "constructor": False,
                  "parameters": parameters or [], "return_type": return_type}
        target.update(target_extra)
        return {"schema_version": 1, "id": PREFIX + name, "enabled": True,
                "package": TEST_PACKAGE, "process": None, "target": target, "action": action,
                "logging": {"enabled": True, "arguments": True, "return_value": True,
                            "stack_trace": True}}

    def add(self, rule):
        result = self.runtime("rules_add", {"rule": rule})
        if not result.get("ok"):
            raise RuntimeError(result)

    def remove(self, rule_id):
        self.runtime("rules_remove", {"id": rule_id})

    def logs(self, rule_id):
        return self.runtime("logs", {"rule_id": rule_id, "package": TEST_PACKAGE, "limit": 100}).get("logs", [])

    def wait(self, predicate, timeout=5):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            value = predicate()
            if value:
                return value
            time.sleep(0.25)
        return None

    def run_rule(self, name, rule, operation, expected):
        self.stop_target()
        self.add(rule)
        try:
            actual = self.target(operation)
            self.results[name] = expected(actual)
        finally:
            self.remove(rule["id"])

    def cleanup(self):
        listed = self.runtime("rules_list")
        for rule in listed.get("rules", []):
            if rule.get("id", "").startswith(PREFIX):
                self.runtime("rules_remove", {"id": rule["id"]})
        self.stop_target()

    def run(self):
        self.cleanup()
        self.run_rule("replace_return_int", self.rule("return_int", "getInt",
                      {"type": "replace_return", "value": 100}), "get_int",
                      lambda item: item.get("value") == 100)
        self.run_rule("replace_return_boolean", self.rule("return_bool", "getBoolean",
                      {"type": "replace_return", "value": False}, return_type="boolean"),
                      "get_boolean", lambda item: item.get("value") is False)
        self.run_rule("replace_return_string", self.rule("return_string", "getString",
                      {"type": "replace_return", "value": "changed"}, return_type="java.lang.String"),
                      "get_string", lambda item: item.get("value") == "changed")
        self.run_rule("replace_argument", self.rule("argument", "add",
                      {"type": "replace_argument", "argument_index": 0, "value": 10},
                      parameters=["int", "int"]), "add", lambda item: item.get("value") == 13)
        self.run_rule("skip", self.rule("skip", "getInt",
                      {"type": "skip_original", "value": 9}), "get_int",
                      lambda item: item.get("value") == 9)
        self.run_rule("overload_int", self.rule("overload_int", "overload",
                      {"type": "replace_return", "value": "hook-int"}, parameters=["int"],
                      return_type="java.lang.String"), "overload_int",
                      lambda item: item.get("value") == "hook-int")
        self.run_rule("overload_string", self.rule("overload_string", "overload",
                      {"type": "replace_return", "value": "hook-string"},
                      parameters=["java.lang.String"], return_type="java.lang.String"),
                      "overload_string", lambda item: item.get("value") == "hook-string")

        for phase in ("record", "before", "after"):
            rule = self.rule(phase, "getInt", {"type": phase})
            self.run_rule(phase, rule, "get_int", lambda item, wanted=phase, rid=rule["id"]:
                          item.get("value") == 42 and bool(self.wait(lambda: any(
                              entry.get("phase") == ("before" if wanted == "before" else "after")
                              for entry in self.logs(rid)))))

        constructor = self.rule("constructor", "", {"type": "record"},
                                parameters=["java.lang.String"], return_type=None,
                                constructor=True)
        self.run_rule("constructor", constructor, "constructor", lambda item: item.get("value") == "provider"
                      and bool(self.wait(lambda: self.logs(constructor["id"]))))

        for name, field, expected_key, value in (("static_field", "staticField", "static_field", 21),
                                                  ("instance_field", "instanceField", "instance_field", 22)):
            rule = self.rule(name, "", {"type": "field_write", "value": value}, return_type=None,
                             field=field)
            self.run_rule(name, rule, "fields", lambda item, key=expected_key, wanted=value:
                          item.get(key) == wanted)

        exception = self.rule("exception", "exceptionMethod", {"type": "record"}, return_type="void")
        self.run_rule("exception", exception, "exception", lambda item: item.get("threw") is True
                      and bool(self.wait(lambda: any("exception" in entry for entry in self.logs(exception["id"])))))

        toggle = self.rule("toggle", "getInt", {"type": "replace_return", "value": 101})
        toggle["enabled"] = False
        self.stop_target(); self.add(toggle)
        disabled_before = self.target("get_int").get("value") == 42
        self.runtime("rules_enable", {"id": toggle["id"]})
        enabled = bool(self.wait(lambda: self.target("get_int").get("value") == 101))
        self.runtime("rules_disable", {"id": toggle["id"]})
        disabled_after = bool(self.wait(lambda: self.target("get_int").get("value") == 42))
        self.results["enable"] = disabled_before and enabled
        self.results["disable"] = disabled_after
        self.remove(toggle["id"])

        reload_rule = self.rule("reload", "getInt", {"type": "replace_return", "value": 30})
        self.stop_target(); self.add(reload_rule); first = self.target("get_int").get("value") == 30
        reload_rule["action"]["value"] = 31
        self.runtime("rules_update", {"rule": reload_rule})
        reload_result = self.runtime("reload")
        second = bool(self.wait(lambda: self.target("get_int").get("value") == 31))
        self.results["reload"] = first and second and reload_result.get("requires_restart") is False
        self.remove(reload_rule["id"])

        persistent = self.rule("persistence", "getInt", {"type": "replace_return", "value": 77})
        self.stop_target(); self.add(persistent)
        self.shell("am force-stop com.luckylca.autocrack.runtime")
        self.results["persistence"] = self.target("get_int").get("value") == 77
        self.remove(persistent["id"])

        delayed = self.rule("delayed", "loaded", {"type": "replace_return", "value": "loaded-hook"},
                            return_type="java.lang.String")
        delayed["target"]["class"] = "com.luckylca.simplehook.delayed.DelayedTarget"
        self.stop_target(); self.add(delayed); self.target("get_int")
        waiting = bool(self.wait(lambda: self._state(delayed["id"]) == "WAITING_FOR_CLASS", timeout=10))
        class_loaded = self.target("load_delayed_class").get("value") == delayed["target"]["class"]
        active = bool(self.wait(lambda: self._state(delayed["id"]) == "ACTIVE", timeout=10))
        loaded = self.target("load_delayed").get("value") == "loaded-hook"
        self.results["class_not_loaded"] = waiting and class_loaded and active and loaded
        self.remove(delayed["id"])

        invalid = self.rule("invalid", "*", {"type": "record"})
        invalid_result = self.runtime("rules_add", {"rule": invalid})
        self.results["invalid_rule"] = (not invalid_result.get("ok")
                                         and invalid_result.get("error", {}).get("code") == "WILDCARD_TOO_BROAD")
        self.results["json_output"] = isinstance(self.runtime("status"), dict)
        self.cleanup()
        return self.results

    def _state(self, rule_id):
        for rule in self.runtime("rules_list").get("rules", []):
            if rule.get("id") == rule_id:
                return rule.get("runtime", {}).get("state")
        return None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    test = DeviceTest(args.serial)
    try:
        results = test.run()
    except Exception as error:
        try:
            test.cleanup()
        except Exception:
            pass
        print(json.dumps({"ok": False, "error": str(error), "results": test.results}, indent=2))
        return 1
    if args.json:
        print(json.dumps({"ok": all(results.values()), "results": results}, indent=2))
    else:
        for name, passed in results.items():
            print(f"{name:<24} {'PASS' if passed else 'FAIL'}")
    return 0 if all(results.values()) else 1


if __name__ == "__main__":
    sys.exit(main())
