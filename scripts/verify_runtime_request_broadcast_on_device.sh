#!/usr/bin/env bash
set -euo pipefail

ADB=${ADB:-/Users/lucky/Library/Android/sdk/platform-tools/adb}
SERIAL=${SERIAL:-}
TARGET=${TARGET:-com.luckylca.runtimeinspector.testapp}
ACTIVITY=${ACTIVITY:-com.luckylca.runtimeinspector.testapp/.MainActivity}
RUNTIME_APK=${RUNTIME_APK:-autocrack-runtime/build/outputs/apk/debug/autocrack-runtime-debug.apk}
TIMEOUT=${TIMEOUT:-8}

adb_cmd=("$ADB")
if [ -n "$SERIAL" ]; then adb_cmd+=("-s" "$SERIAL"); fi

b64() {
  python3 -c 'import base64,json,sys; raw=sys.argv[1]; json.loads(raw); print(base64.b64encode(raw.encode()).decode())' "$1"
}

call_provider() {
  local method="$1"
  local payload
  if [ $# -ge 2 ]; then payload="$2"; else payload='{}'; fi
  "${adb_cmd[@]}" shell content call --uri content://com.luckylca.autocrack.runtime --method "$method" --extra base64:s:$(b64 "$payload")
}

json_from_bundle() {
  python3 -c "import json,sys; text=sys.stdin.read(); start=text.find('{\"ok\"'); start >= 0 or (print(text.strip()) or sys.exit(1)); obj,_=json.JSONDecoder().raw_decode(text[start:]); print(json.dumps(obj,ensure_ascii=False))"
}

if [ ! -x "$ADB" ]; then echo "missing adb: $ADB" >&2; exit 2; fi
if [ ! -f "$RUNTIME_APK" ]; then echo "missing runtime apk: $RUNTIME_APK" >&2; exit 2; fi

echo "== device =="
"${adb_cmd[@]}" devices -l

echo "== install runtime apk =="
"${adb_cmd[@]}" install -r "$RUNTIME_APK"

echo "== package paths =="
current=$("${adb_cmd[@]}" shell pm path com.luckylca.autocrack.runtime | sed 's/^package://' | tr -d '\r')
echo "pm_path=$current"
"${adb_cmd[@]}" shell su -c 'cp /data/adb/lspd/config/modules_config.db /sdcard/autocrack_modules_config.db && chmod 0644 /sdcard/autocrack_modules_config.db' >/dev/null 2>&1 || true
"${adb_cmd[@]}" pull /sdcard/autocrack_modules_config.db /tmp/autocrack_modules_config.db >/dev/null 2>&1 || true
"${adb_cmd[@]}" shell rm /sdcard/autocrack_modules_config.db >/dev/null 2>&1 || true
python3 - <<'PY' || true
import sqlite3, pathlib
p=pathlib.Path('/tmp/autocrack_modules_config.db')
if not p.exists():
    print('lsposed_db=unavailable')
    raise SystemExit(0)
con=sqlite3.connect(p)
for row in con.execute("select apk_path from modules where module_pkg_name='com.luckylca.autocrack.runtime'"):
    print('lsposed_apk_path='+row[0])
for row in con.execute("select enabled from modules_state where module_pkg_name='com.luckylca.autocrack.runtime' and user_id=0"):
    print('lsposed_enabled='+str(row[0]))
print('lsposed_scope='+','.join(r[0] for r in con.execute("select app_pkg_name from scope where module_pkg_name='com.luckylca.autocrack.runtime' and user_id=0 order by app_pkg_name")))
PY

echo "== restart target =="
"${adb_cmd[@]}" logcat -c || true
"${adb_cmd[@]}" shell am force-stop "$TARGET" || true
"${adb_cmd[@]}" shell am start -W -n "$ACTIVITY"
sleep 2

echo "== status =="
call_provider runtime_status '{}' | json_from_bundle
SIMPLEHOOK_ANDROID_SHELL="${adb_cmd[*]} shell" python3 toolpacks/simplehook/libexec/simplehook_cli.py status --json || true

echo "== runtime request =="
AUTOCRACK_ANDROID_SHELL="${adb_cmd[*]} shell" python3 toolpacks/runtime-inspect/libexec/runtime_inspect_cli.py capabilities --package "$TARGET" --timeout "$TIMEOUT" --json

echo "== logcat evidence =="
"${adb_cmd[@]}" logcat -d -v time | grep -Ei 'RuntimeInspector request broadcast|provider pending unavailable|provider complete unavailable|result event|runtime_complete|Runtime event rejected|AppsFilter|Unknown authority|AutoCrack Runtime attached' | tail -120 || true
