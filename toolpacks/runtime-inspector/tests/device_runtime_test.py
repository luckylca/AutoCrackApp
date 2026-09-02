#!/usr/bin/env python3
import argparse, base64, json, subprocess, sys, time, xml.etree.ElementTree as ET

AUTH = "com.luckylca.runtimeinspector.runtime"
PKG = "com.luckylca.runtimeinspector.testapp"


class Test:
    def __init__(self, serial, adb="adb"):
        self.adb = [adb, "-s", serial]; self.results = {}

    def shell(self, *args, timeout=30):
        p = subprocess.run(self.adb + ["shell", *args], text=True, capture_output=True, timeout=timeout)
        if p.returncode: raise RuntimeError((p.stderr or p.stdout).strip())
        return p.stdout + p.stderr

    @staticmethod
    def parse(output):
        i = output.find('{"ok"')
        if i < 0: raise RuntimeError(output.strip())
        return json.JSONDecoder().raw_decode(output[i:])[0]

    def provider(self, method, request=None):
        raw = json.dumps(request or {}, separators=(",", ":")).encode(); b64 = base64.b64encode(raw).decode()
        return self.parse(self.shell("content", "call", "--uri", f"content://{AUTH}", "--method", method, "--extra", f"base64:s:{b64}"))

    def request(self, payload, timeout=6):
        submitted = self.provider("submit", payload); rid = submitted["request_id"]; deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            result = self.provider("result", {"request_id": rid})
            if not result.get("pending"):
                value = result.get("result", {})
                if not value.get("ok"): raise RuntimeError(value)
                return value
            time.sleep(.25)
        raise RuntimeError(f"timeout {rid}")

    def start_target(self):
        try: self.shell("su", "-c", "input keyevent 224")
        except Exception: pass
        self.shell("am", "force-stop", PKG); self.shell("am", "start", "-W", "-n", f"{PKG}/.MainActivity"); time.sleep(.8)

    def target_pid(self):
        value = self.shell("pidof", PKG).strip()
        return int(value.split()[0]) if value else -1

    def tree(self, listeners=True):
        return self.request({"package": PKG, "kind": "view_tree", "include_listeners": listeners})

    @staticmethod
    def node(tree, suffix):
        for n in tree["nodes"]:
            if str(n.get("resource_name", "")).endswith(suffix): return n
        raise RuntimeError(f"node not found: {suffix}")

    def ui_texts(self):
        self.shell("uiautomator", "dump", "/sdcard/runtime_inspector_ui.xml")
        xml = self.shell("cat", "/sdcard/runtime_inspector_ui.xml")
        return [node.attrib.get("text", "") for node in ET.fromstring(xml).iter("node")]

    def fresh_target(self):
        self.provider("clear", {})
        self.start_target()

    def run(self):
        # Keep each capability group short. Some Xiaomi/HyperOS builds freeze a locked-screen
        # background process aggressively; restarting also guarantees that every node_id below
        # comes from the current process instead of accidentally reusing a stale View identity.
        self.fresh_target()
        windows = self.request({"package": PKG, "kind": "windows"})
        self.results["windows"] = windows.get("root_count", 0) >= 1
        self.results["target_process_answer"] = windows.get("runtime_package") == PKG and windows.get("runtime_pid") == self.target_pid()

        tree = self.tree(True)
        target = self.node(tree, ":id/inspect_target_text")
        button = self.node(tree, ":id/inspect_click_button")
        self.results["tree"] = tree.get("node_count", 0) >= 5 and target.get("text") == "Inspector Target"
        listeners = button.get("listeners", {})
        self.results["listener"] = "TargetClickListener" in listeners.get("mOnClickListener", "")
        bounds = target["bounds"]
        x = (bounds[0] + bounds[2]) // 2
        y = (bounds[1] + bounds[3]) // 2
        hit = self.request({"package": PKG, "kind": "view_at", "x": x, "y": y,
                            "include_listeners": True, "include_hidden": not target.get("shown", False)})
        self.results["hit_test"] = any(str(n.get("resource_name", "")).endswith(":id/inspect_target_text")
                                       for n in hit.get("candidates", []))

        self.fresh_target()
        target = self.node(self.tree(False), ":id/inspect_target_text")
        changed = "Changed By Runtime Inspector"
        self.request({"package": PKG, "kind": "view_action", "node_id": target["node_id"],
                      "action": {"type": "set_text", "value": changed}})
        changed_node = self.node(self.tree(False), ":id/inspect_target_text")
        self.results["set_text_effect"] = changed_node.get("text") == changed
        if target.get("shown", False):
            time.sleep(.2)
            self.results["set_text_visual"] = changed in self.ui_texts()

        self.fresh_target()
        nested = self.node(self.tree(False), ":id/inspect_nested_label")
        self.request({"package": PKG, "kind": "view_action", "node_id": nested["node_id"],
                      "action": {"type": "set_visibility", "value": "gone"}})
        after = self.node(self.tree(False), ":id/inspect_nested_label")
        self.results["visibility_effect"] = after.get("visibility") == 8 and not after.get("shown")

        self.fresh_target()
        dialog_button = self.node(self.tree(False), ":id/inspect_dialog_button")
        self.request({"package": PKG, "kind": "view_action", "node_id": dialog_button["node_id"],
                      "action": {"type": "perform_click"}})
        time.sleep(.3)
        dialog_windows = self.request({"package": PKG, "kind": "windows"})
        dialog_tree = self.tree(False)
        self.results["dialog_window"] = dialog_windows.get("root_count", 0) >= 2 and any(
            n.get("text") == "Inspector Dialog Window" for n in dialog_tree.get("nodes", []))
        return self.results


def main():
    p = argparse.ArgumentParser(); p.add_argument("--serial", required=True); p.add_argument("--adb", default="adb"); p.add_argument("--json", action="store_true"); a = p.parse_args()
    test = Test(a.serial, a.adb)
    try: results = test.run(); ok = all(results.values())
    except Exception as e: print(json.dumps({"ok": False, "error": str(e), "results": test.results}, indent=2)); return 1
    print(json.dumps({"ok": ok, "results": results}, indent=2) if a.json else "\n".join(f"{k:<24} {'PASS' if v else 'FAIL'}" for k,v in results.items()))
    return 0 if ok else 1


if __name__ == "__main__": sys.exit(main())
