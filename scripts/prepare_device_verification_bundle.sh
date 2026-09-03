#!/usr/bin/env bash
set -euo pipefail

ROOTFS_ZIP=""
OUT_ROOT=".local-artifacts"
while [ $# -gt 0 ]; do
  case "$1" in
    --rootfs-zip)
      ROOTFS_ZIP="${2:?missing --rootfs-zip value}"; shift 2 ;;
    --out-root)
      OUT_ROOT="${2:?missing --out-root value}"; shift 2 ;;
    *)
      echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

require_file() {
  local path="$1" label="$2"
  if [ ! -f "$path" ]; then
    echo "missing $label: $path" >&2
    exit 1
  fi
}

require_file app/build/outputs/apk/debug/app-debug.apk "AutoCrackApp debug APK"
require_file autocrack-runtime/build/outputs/apk/debug/autocrack-runtime-debug.apk "AutoCrack Runtime debug APK"
require_file runtime-inspector-test-app/build/outputs/apk/debug/runtime-inspector-test-app-debug.apk "Runtime Inspector test APK"
require_file simplehook-test-app/build/outputs/apk/debug/simplehook-test-app-debug.apk "SimpleHook test APK"
STAMP=$(date +%Y%m%d-%H%M%S)
OUT="$OUT_ROOT/device-verification-$STAMP"
mkdir -p "$OUT/apk" "$OUT/rootfs" "$OUT/toolpacks" "$OUT/manifests" "$OUT/logs" "$OUT/docs" "$OUT/scripts"
cp app/build/outputs/apk/debug/app-debug.apk "$OUT/apk/AutoCrackApp-debug.apk"
cp autocrack-runtime/build/outputs/apk/debug/autocrack-runtime-debug.apk "$OUT/apk/AutoCrackRuntime-debug.apk"
cp runtime-inspector-test-app/build/outputs/apk/debug/runtime-inspector-test-app-debug.apk "$OUT/apk/RuntimeInspectorTest-debug.apk"
cp simplehook-test-app/build/outputs/apk/debug/simplehook-test-app-debug.apk "$OUT/apk/SimpleHookTestApp-debug.apk"

if [ -n "$ROOTFS_ZIP" ]; then
  require_file "$ROOTFS_ZIP" "rootfs package"
  cp "$ROOTFS_ZIP" "$OUT/rootfs/$(basename "$ROOTFS_ZIP")"
else
  echo "rootfs zip not provided; build/download AutoCrackApp-debian-bookworm-arm64-rootfs.zip from rootfs-package.yml" > "$OUT/rootfs/ROOTFS_PENDING.txt"
fi

copy_doc_if_present() {
  local src="$1" dst="$2"
  if [ -f "$src" ]; then cp "$src" "$dst"; fi
}

copy_doc_if_present docs/layout-inspect-research/TEST_MATRIX.md "$OUT/docs/TEST_MATRIX.md"
copy_doc_if_present docs/layout-inspect-research/FINAL_REPORT.md "$OUT/docs/FINAL_REPORT.md"
copy_doc_if_present docs/layout-inspect-research/CROSS_TOOL_INTEGRATION.md "$OUT/docs/CROSS_TOOL_INTEGRATION.md"
copy_doc_if_present scripts/validate_cross_tool_contract.py "$OUT/scripts/validate_cross_tool_contract.py"
copy_doc_if_present scripts/verify_runtime_request_broadcast_on_device.sh "$OUT/scripts/verify_runtime_request_broadcast_on_device.sh"
copy_doc_if_present toolpacks/simplehook/tests/device_runtime_test.py "$OUT/scripts/simplehook_device_runtime_test.py"

build_runtime_toolpack() {
  local name="$1"
  local script="toolpacks/$name/build_toolpack.py"
  require_file "$script" "$name build script"
  python3 "$script" > "$OUT/logs/${name}-build.log"
  local zip_path
  zip_path=$(find "toolpacks/$name/dist" -maxdepth 1 -type f -name '*toolpack*.zip' -print | sort | head -1 || true)
  if [ -z "$zip_path" ]; then
    echo "missing built toolpack zip for $name" >&2
    exit 1
  fi
  cp "$zip_path" "$OUT/toolpacks/$(basename "$zip_path")"
  require_file "toolpacks/$name/dist/manifest.json" "$name manifest"
  cp "toolpacks/$name/dist/manifest.json" "$OUT/manifests/${name}.manifest.json"
}

for name in ui-inspect runtime-inspect memory-dump runtime-control simplehook; do
  build_runtime_toolpack "$name"
done

python3 scripts/validate_cross_tool_contract.py --json > "$OUT/logs/cross-tool-contract.json"

python3 toolpacks/pcap-analysis/build_toolpack.py --output-dir "$OUT/pcap-build" > "$OUT/logs/pcap-build.log"
find "$OUT/pcap-build" -maxdepth 2 -type f -name '*toolpack*.zip' -exec cp {} "$OUT/toolpacks/" \;
find "$OUT/pcap-build" -maxdepth 2 -type f -name '*manifest*.json' -exec cp {} "$OUT/manifests/pcap-analysis-manifest.json" \; || true

copy_newest() {
  local pattern="$1" name="$2"
  local src=""
  if [ -d .local-artifacts/cloud-toolpacks ]; then
    src=$(find .local-artifacts/cloud-toolpacks -type f -name "$pattern" -print 2>/dev/null | xargs ls -t 2>/dev/null | head -1 || true)
  fi
  if [ -n "$src" ] && [ -f "$src" ]; then
    cp "$src" "$OUT/toolpacks/$name"
    local mf
    mf=$(dirname "$src")/manifest.json
    if [ -f "$mf" ]; then cp "$mf" "$OUT/manifests/${name%.zip}.manifest.json"; fi
  else
    echo "MISSING $pattern" >> "$OUT/logs/missing-toolpacks.txt"
  fi
}

copy_newest 'AutoCrackApp-android-frida-17.17.0-toolpack.zip' 'AutoCrackApp-android-frida-17.17.0-toolpack.zip'
copy_newest 'AutoCrackApp-android-lldb-server-seize-runtime-stop-toolpack.zip' 'AutoCrackApp-android-lldb-server-seize-runtime-stop-toolpack.zip'
copy_newest 'AutoCrackApp-rizin-deep-static-toolpack.zip' 'AutoCrackApp-rizin-deep-static-toolpack.zip'
copy_newest 'AutoCrackApp-perfetto-analysis-toolpack.zip' 'AutoCrackApp-perfetto-analysis-toolpack.zip'
copy_newest 'AutoCrackApp-apk-dex-static-toolpack.zip' 'AutoCrackApp-apk-dex-static-toolpack.zip'
copy_newest 'AutoCrackApp-elf-native-static-toolpack.zip' 'AutoCrackApp-elf-native-static-toolpack.zip'

{
  echo '# AutoCrackApp device verification bundle'
  echo "created=$STAMP"
  echo "project_head=$(git rev-parse --short HEAD 2>/dev/null || true)"
  echo "branch=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || true)"
  echo
  echo '## APK'
  (cd "$OUT" && shasum -a 256 apk/* 2>/dev/null || true)
  echo
  echo '## Rootfs'
  (cd "$OUT" && shasum -a 256 rootfs/* 2>/dev/null || true)
  echo
  echo '## Toolpacks'
  (cd "$OUT" && shasum -a 256 toolpacks/* 2>/dev/null || true)
  echo
  echo '## Manifests'
  (cd "$OUT" && shasum -a 256 manifests/* 2>/dev/null || true)
  echo
  echo '## Verification docs/scripts'
  (cd "$OUT" && shasum -a 256 docs/* scripts/* 2>/dev/null || true)
  echo
  echo '## Files'
  find "$OUT" -maxdepth 3 -type f | sort | sed "s#^$OUT/##"
  echo
  echo '## Host checks included'
  echo '- logs/cross-tool-contract.json records the Phase I host-side Toolpack/flow contract check.'
  echo '- scripts/simplehook_device_runtime_test.py is copied for owned-device SimpleHook regression runs after LSPosed scope refresh.'
  echo '- scripts/verify_runtime_request_broadcast_on_device.sh replays the Stage 52 runtime request broadcast fallback on an owned device.'
  echo '- docs/CROSS_TOOL_INTEGRATION.md describes manual target-positive checks for the four required cross-tool flows.'
} > "$OUT/VERIFY.md"
ln -sfn "$(basename "$OUT")" "$OUT_ROOT/device-verification-current"
printf '%s\n' "$OUT"
