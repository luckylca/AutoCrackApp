#!/usr/bin/env bash
set -euo pipefail

ADB=${ADB:-/Users/lucky/Library/Android/sdk/platform-tools/adb}
SERIAL=${SERIAL:-}
TARGET=${TARGET:-com.luckylca.runtimeinspector.testapp}
ACTIVITY=${ACTIVITY:-com.luckylca.runtimeinspector.testapp/.MainActivity}
APP_PACKAGE=${APP_PACKAGE:-com.luckylca.autocrack}
DEBUG_ACTIVITY=${DEBUG_ACTIVITY:-com.luckylca.autocrack/.debug.DebugMobileAgentExecActivity}
EXEC_TIMEOUT_MS=${EXEC_TIMEOUT_MS:-120000}
REPORT_REL=debug-validation/mobile-agent-exec-report.json
SCRIPT_REL=debug-validation/pi-agent-runtime-e2e.sh

adb_cmd=("$ADB")
if [ -n "$SERIAL" ]; then adb_cmd+=("-s" "$SERIAL"); fi

if [ ! -x "$ADB" ]; then
  echo "missing adb: $ADB" >&2
  exit 2
fi

app_uid=$("${adb_cmd[@]}" shell pm list packages -U "$APP_PACKAGE" | tr -d '\r' | sed -n "s/^package:$APP_PACKAGE uid:\([0-9][0-9]*\)$/\1/p" | head -1)
if [ -z "$app_uid" ]; then
  echo "unable to resolve uid for $APP_PACKAGE" >&2
  exit 2
fi

tmp_script=$(mktemp -t autocrack-pi-agent-e2e.XXXXXX.sh)
trap 'rm -f "$tmp_script"' EXIT
cat >"$tmp_script" <<'SH'
set -euo pipefail
target="${TARGET_PACKAGE:-com.luckylca.runtimeinspector.testapp}"

echo "DISCOVERY"
for command_name in android-shell ui-inspect runtime-inspect memory-dump runtime-control simplehook; do
  command_path="$(command -v "$command_name")"
  test -n "$command_path"
  echo "$command_name=$command_path"
done

echo "SKILLS"
for toolpack in ui-inspect runtime-inspect memory-dump runtime-control simplehook; do
  skill="/opt/autocrack/toolpacks/active/$toolpack/SKILL.md"
  test -s "$skill"
  printf '%s=' "$toolpack"
  sed -n '1p' "$skill"
done

android-shell id | grep -F 'uid=0(root)' >/dev/null

runtime-inspect doctor --package "$target" --timeout 10 --json > /workspace/runtime-doctor.json
ui-inspect windows --package "$target" --timeout 10 --json > /workspace/ui-windows.json
runtime-inspect process --package "$target" --timeout 10 --json > /workspace/runtime-process.json
memory-dump capabilities --package "$target" --timeout 10 --json > /workspace/memory-capabilities.json
runtime-control status --json > /workspace/runtime-control-status.json
simplehook doctor --json > /workspace/simplehook-doctor.json

python3 - <<'PY'
import json

paths = {
    "doctor": "/workspace/runtime-doctor.json",
    "ui": "/workspace/ui-windows.json",
    "process": "/workspace/runtime-process.json",
    "memory": "/workspace/memory-capabilities.json",
    "control": "/workspace/runtime-control-status.json",
    "hook": "/workspace/simplehook-doctor.json",
}
data = {name: json.load(open(path, encoding="utf-8")) for name, path in paths.items()}
for name, result in data.items():
    if result.get("ok") is not True:
        raise SystemExit(f"{name} failed: {result}")
if data["doctor"].get("healthy") is not True:
    raise SystemExit(f"doctor unhealthy: {data['doctor']}")
ui = data["ui"]
if not (
    ui.get("root_count", 0) >= 1
    or len(ui.get("roots", [])) >= 1
    or len(ui.get("windows", [])) >= 1
):
    raise SystemExit(f"no UI roots/windows: {ui}")
process = data["process"]
package_name = (
    process.get("package")
    or process.get("runtime_package")
    or process.get("result", {}).get("package")
)
if package_name not in (None, "com.luckylca.runtimeinspector.testapp"):
    raise SystemExit(f"wrong target package: {process}")
for name in ("doctor", "ui", "process", "memory"):
    print(f"{name}_target_thaws={data[name].get('client_target_thaws', 0)}")
print("CHECKS=discovery,skills,root,doctor,ui,process,memory,control,simplehook")
print(
    "TARGET_PID="
    + str(
        process.get("pid")
        or process.get("runtime_pid")
        or data["doctor"].get("pid")
        or data["doctor"].get("runtime_pid")
    )
)
print("PI_AGENT_RUNTIME_TOOLPACK_E2E_OK")
PY
SH

remote_tmp=/data/local/tmp/autocrack-pi-agent-runtime-e2e.sh
private_dir=/data/user/0/$APP_PACKAGE/files/debug-validation
"${adb_cmd[@]}" push "$tmp_script" "$remote_tmp" >/dev/null
"${adb_cmd[@]}" shell "su -c 'mkdir -p $private_dir; cp $remote_tmp $private_dir/pi-agent-runtime-e2e.sh; chown $app_uid:$app_uid $private_dir/pi-agent-runtime-e2e.sh; chmod 600 $private_dir/pi-agent-runtime-e2e.sh; rm -f $private_dir/mobile-agent-exec-report.json'"

clear_payload=$(python3 - <<'PY'
import base64, json
print(base64.b64encode(json.dumps({}).encode()).decode())
PY
)
"${adb_cmd[@]}" shell content call --uri content://com.luckylca.autocrack.runtime --method runtime_clear --extra "base64:s:$clear_payload" >/dev/null 2>&1 || true

"${adb_cmd[@]}" shell am force-stop "$TARGET" || true
sleep 1
"${adb_cmd[@]}" logcat -c || true
"${adb_cmd[@]}" shell am start -W -n "$ACTIVITY" >/dev/null
sleep 1
"${adb_cmd[@]}" shell am start -W -n "$DEBUG_ACTIVITY" --es script_file "$SCRIPT_REL" --el timeout_ms "$EXEC_TIMEOUT_MS" >/dev/null

report=""
for _ in $(seq 1 480); do
  if "${adb_cmd[@]}" shell "su -c 'test -s $private_dir/mobile-agent-exec-report.json'" >/dev/null 2>&1; then
    report=$("${adb_cmd[@]}" shell "su -c 'cat $private_dir/mobile-agent-exec-report.json'")
    break
  fi
  sleep 0.25
done

if [ -z "$report" ]; then
  echo "mobile-agent report was not produced" >&2
  exit 1
fi

REPORT="$report" python3 - <<'PY'
import json, os

report = json.loads(os.environ["REPORT"])
result = report.get("result", {})
print(
    "agent_success",
    report.get("success"),
    "installedToolpacks",
    report.get("installedToolpacks"),
)
print(
    "exec_ok",
    result.get("ok"),
    "exit",
    result.get("exitCode"),
    "timedOut",
    result.get("timedOut"),
)
print(result.get("stdout", ""))
if result.get("stderr"):
    print("stderr:", result["stderr"])
assert report.get("success") is True, report
assert result.get("ok") is True, report
assert "PI_AGENT_RUNTIME_TOOLPACK_E2E_OK" in result.get("stdout", ""), report
assert report.get("installedToolpacks", 0) >= 6, report
print("FINAL_PI_AGENT_E2E_PASS")
PY

sleep 1
"${adb_cmd[@]}" logcat -d -v brief | python3 -c '
import sys

lines = sys.stdin.read().splitlines()
accepted = [line for line in lines if "RuntimeInspector request accepted:" in line]
binder = [line for line in lines if "Runtime request delivered via Binder:" in line]
cached = [line for line in lines if "Runtime request Binder cached:" in line]
failed = [line for line in lines if "Runtime request Binder delivery failed" in line]
request_denied = [
    line for line in lines
    if "Greezer Denial" in line and "runtime.REQUEST" in line
]
print("REQUEST_ACCEPTED", len(accepted))
print("BINDER_CACHED", len(cached))
print("BINDER_DELIVERED", len(binder))
print("BINDER_DELIVERY_FAILED", len(failed))
print("REQUEST_GREEZER_DENIAL", len(request_denied))
assert len(accepted) >= 4, accepted
assert len(cached) >= 1, cached
assert len(binder) >= 3, binder
assert len(failed) == 0, failed
assert len(request_denied) == 0, request_denied
'
echo "FINAL_PI_AGENT_PROTOCOL_PASS"
