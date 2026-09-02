import json
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
CLI = ROOT / "bin" / "simplehook"
EXAMPLE = ROOT / "examples" / "replace-return-int.json"
sys.path.insert(0, str(ROOT / "libexec"))

from simplehook_cli import read_lsposed_module_status


class SimpleHookCliTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.environment = dict(os.environ, SIMPLEHOOK_HOME=self.temporary.name)

    def tearDown(self):
        self.temporary.cleanup()

    def run_cli(self, *arguments, ok=True, stdin=None):
        completed = subprocess.run([str(CLI), *arguments], input=stdin, text=True, capture_output=True, env=self.environment)
        if ok and completed.returncode != 0:
            self.fail(f"CLI failed: {completed.stderr}\n{completed.stdout}")
        stream = completed.stdout if completed.returncode == 0 else completed.stderr
        return completed, json.loads(stream)

    def test_json_crud_reload_and_persistence(self):
        _, added = self.run_cli("rules", "add", str(EXAMPLE), "--json")
        self.assertTrue(added["ok"])
        _, listed = self.run_cli("rules", "list", "--json")
        self.assertEqual("WAITING_FOR_PROCESS", listed["rules"][0]["runtime"]["state"])
        self.run_cli("rules", "disable", "test_get_int", "--json")
        _, shown = self.run_cli("rules", "show", "test_get_int", "--json")
        self.assertFalse(shown["rule"]["enabled"])
        self.run_cli("rules", "enable", "test_get_int", "--json")
        _, reload_result = self.run_cli("reload", "--json")
        self.assertFalse(reload_result["requires_restart"])
        _, status = self.run_cli("status", "--json")
        self.assertEqual(1, status["rules"]["total"])
        self.run_cli("rules", "remove", "test_get_int", "--json")

    def test_validate_dry_run_invalid_and_id_mismatch(self):
        _, valid = self.run_cli("rules", "validate", str(EXAMPLE), "--json")
        self.assertTrue(valid["valid"])
        _, dry = self.run_cli("rules", "add", str(EXAMPLE), "--dry-run", "--json")
        self.assertTrue(dry["dry_run"])
        invalid = EXAMPLE.read_text().replace('"method": "getInt"', '"method": "*"')
        completed, error = self.run_cli("rules", "add", "-", "--json", ok=False, stdin=invalid)
        self.assertNotEqual(0, completed.returncode)
        self.assertEqual("WILDCARD_TOO_BROAD", error["error"]["code"])
        self.run_cli("rules", "add", str(EXAMPLE), "--json")
        _, mismatch = self.run_cli("rules", "update", "different", str(EXAMPLE), "--json", ok=False)
        self.assertEqual("ID_MISMATCH", mismatch["error"]["code"])

    def test_schema_rejects_unknown_properties_and_wrong_types(self):
        document = json.loads(EXAMPLE.read_text())
        document["eval"] = "not allowed"
        _, unknown = self.run_cli("rules", "validate", "-", "--json", ok=False,
                                  stdin=json.dumps(document))
        self.assertEqual("INVALID_SCHEMA", unknown["error"]["code"])
        document.pop("eval")
        document["enabled"] = "true"
        _, wrong_type = self.run_cli("rules", "validate", "-", "--json", ok=False,
                                     stdin=json.dumps(document))
        self.assertEqual("INVALID_SCHEMA", wrong_type["error"]["code"])

    def test_log_filters_and_jsonl(self):
        log_path = Path(self.temporary.name) / "simplehook.jsonl"
        log_path.write_text('\n'.join([
            json.dumps({"rule_id": "a", "package": "one", "timestamp": 1}),
            json.dumps({"rule_id": "b", "package": "two", "timestamp": 2}),
        ]) + '\n')
        completed = subprocess.run([str(CLI), "logs", "--rule", "a"], text=True, capture_output=True, env=self.environment)
        self.assertEqual(0, completed.returncode)
        self.assertEqual("a", json.loads(completed.stdout)["rule_id"])
        _, result = self.run_cli("logs", "--package", "two", "--json")
        self.assertEqual("b", result["logs"][0]["rule_id"])

    def test_help_lists_required_commands(self):
        completed = subprocess.run([str(CLI), "--help"], text=True, capture_output=True, env=self.environment)
        self.assertEqual(0, completed.returncode)
        for command in ("status", "environment", "doctor", "rules", "logs", "apply", "reload", "inspect"):
            self.assertIn(command, completed.stdout)

    def test_offline_status_does_not_claim_module_is_disabled(self):
        _, status = self.run_cli("status", "--json")
        self.assertIsNone(status["runtime"]["module_enabled"])
        self.assertFalse(status["runtime"]["runtime_attached"])
        self.assertEqual("unavailable", status["module"]["source"])

    def test_reads_enabled_module_and_scope_from_lsposed_database(self):
        database = Path(self.temporary.name) / "modules_config.db"
        connection = sqlite3.connect(database)
        connection.executescript("""
            CREATE TABLE modules(module_pkg_name TEXT PRIMARY KEY NOT NULL, apk_path TEXT);
            CREATE TABLE modules_state(
                module_pkg_name TEXT NOT NULL, user_id INTEGER NOT NULL, enabled BOOLEAN DEFAULT 0,
                scope_request_blocked BOOLEAN DEFAULT 0,
                PRIMARY KEY(module_pkg_name, user_id)
            );
            CREATE TABLE scope(
                module_pkg_name TEXT NOT NULL, app_pkg_name TEXT NOT NULL, user_id INTEGER NOT NULL,
                PRIMARY KEY(module_pkg_name, app_pkg_name, user_id)
            );
            INSERT INTO modules VALUES('com.luckylca.autocrack.runtime', '/data/app/runtime/base.apk');
            INSERT INTO modules_state VALUES('com.luckylca.autocrack.runtime', 0, 1, 0);
            INSERT INTO scope VALUES('com.luckylca.autocrack.runtime', 'com.example.target', 0);
        """)
        connection.commit()
        connection.close()

        result = read_lsposed_module_status(database)

        self.assertTrue(result["installed"])
        self.assertTrue(result["enabled"])
        self.assertEqual(["com.example.target"], result["scope_packages"])
        self.assertEqual("lsposed_database", result["source"])

    def test_rejects_invalid_lsposed_database_without_guessing(self):
        database = Path(self.temporary.name) / "invalid.db"
        sqlite3.connect(database).close()

        result = read_lsposed_module_status(database)

        self.assertIsNone(result["enabled"])
        self.assertEqual("LSPOSED_STATUS_UNAVAILABLE", result["error"]["code"])


if __name__ == "__main__":
    unittest.main()
