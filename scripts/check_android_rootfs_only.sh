#!/usr/bin/env bash
set -euo pipefail

# Static guard for AutoCrackApp's final Agent runtime target.
# It intentionally checks only runtime-relevant source/toolpack paths. Build caches,
# GitHub Actions, docs, and developer scripts may mention desktop machines because
# toolpack building and debug validation are allowed outside the final runtime.

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
cd "$ROOT"

paths=(
  "app/src/main"
  "toolpacks"
)

network_executor="app/src/main/java/com/luckylca/autocrack/agent/AgentAndroidNetworkToolExecutor.kt"
agent_session_factory="app/src/main/java/com/luckylca/autocrack/agent/AgentToolSessionFactory.kt"
tool_catalog="app/src/main/assets/runtime/tool-catalog-v1.json"
dynamic_tool_catalog="app/src/main/assets/runtime/dynamic-host-tool-catalog-v1.json"
device_diagnostics_executor="app/src/main/java/com/luckylca/autocrack/agent/AgentDeviceDiagnosticsToolExecutor.kt"
frida_executor="app/src/main/java/com/luckylca/autocrack/agent/AgentFridaToolExecutor.kt"
native_executor="app/src/main/java/com/luckylca/autocrack/agent/AgentNativeToolExecutor.kt"
frida_client="toolpacks/frida/frida_autocrack_client.py"
android_manifest="app/src/main/AndroidManifest.xml"
foreground_service="app/src/main/java/com/luckylca/autocrack/runtime/PtySessionForegroundService.kt"
agent_execution_foreground_service="app/src/main/java/com/luckylca/autocrack/runtime/AgentExecutionForegroundService.kt"

patterns=(
  '/Users/'
  'platform-tools'
  'adb shell'
  'adb -s'
  'darwin'
  'macOS'
  'Mac host'
  'host worker'
  'Host-side'
  'host-side'
  'linux-x64'
)

failed=0
for pattern in "${patterns[@]}"; do
  matches=$(grep -RIn \
    --exclude-dir=.git --exclude-dir=.gradle --exclude-dir=.cxx --exclude-dir=build \
    --exclude='*.md' --exclude='build_toolpack.py' --exclude='*.lock.json' --exclude='VERSION' \
    -- "$pattern" "${paths[@]}" 2>/dev/null || true)
  if [ -n "$matches" ]; then
    printf 'Forbidden final-runtime dependency marker found: %s\n%s\n' "$pattern" "$matches" >&2
    failed=1
  fi
done

require_literal() {
  local path="$1"
  local literal="$2"
  local label="$3"
  if ! grep -Fq -- "$literal" "$path" 2>/dev/null; then
    printf 'Required final-runtime/network policy marker missing: %s\n  file: %s\n  literal: %s\n' "$label" "$path" "$literal" >&2
    failed=1
  fi
}

forbidden_network_args=(
  'interface'
  'iptables_rule'
  'nft_rule'
  'proxy_config_path'
  'surfing_config_patch'
  'install_ca_certificate'
  'vpn_mode'
  'mitm_enabled'
  'raw_filter'
)

if [ -f "$network_executor" ]; then
  require_literal "$network_executor" 'private val allowCapture: Boolean = false' 'packet capture is disabled by default'
  require_literal "$network_executor" 'requireKnownArguments(TOOL_PCAP_START, arguments, PCAP_START_ARGUMENT_KEYS)' 'pcap start rejects unknown model-supplied arguments'
  require_literal "$network_executor" 'val PCAP_START_ARGUMENT_KEYS = setOf("duration_seconds", "snaplen", "max_bytes")' 'pcap start exposes only bounded duration/snaplen/size arguments'
  require_literal "$network_executor" 'File(layout.binRoot, "tcpdump")' 'pcap capture prefers app-managed tcpdump before system fallback'
  require_literal "$network_executor" '.put("surfingConfigTouched", false)' 'pcap result declares Surfing config untouched'
  require_literal "$network_executor" '.put("vpnUsed", false)' 'pcap result declares no VPN usage'
  require_literal "$network_executor" '.put("httpsDecrypted", false)' 'pcap result declares no HTTPS decryption'

  for argument in "${forbidden_network_args[@]}"; do
    # Reject production parameters with these exact JSON-style names while still
    # allowing ordinary prose such as "network interfaces" in descriptions.
    quoted_argument="\"$argument\""
    if grep -Fq -- "$quoted_argument" "$network_executor"; then
      printf 'Forbidden network capture argument exposed in production executor: %s\n%s\n' "$argument" "$(grep -Fn -- "$quoted_argument" "$network_executor")" >&2
      failed=1
    fi
  done
else
  printf 'Network executor missing: %s\n' "$network_executor" >&2
  failed=1
fi

if [ -f "$agent_session_factory" ]; then
  require_literal "$agent_session_factory" 'allowCapture = allowDynamicTools' 'pcap tools require explicit dynamic-tool authorization'
else
  printf 'Agent tool session factory missing: %s\n' "$agent_session_factory" >&2
  failed=1
fi


if [ -f "$device_diagnostics_executor" ]; then
  require_literal "$device_diagnostics_executor" 'const val TOOL_DEVICE_DIAGNOSTICS = "android_device_diagnostics"' 'device diagnostics tool is registered as Android scoped'
  require_literal "$device_diagnostics_executor" 'const val TOOL_TOOLING_STATUS = "android_tooling_status"' 'tooling status tool is registered as Android scoped'
else
  printf 'Device diagnostics executor missing: %s\n' "$device_diagnostics_executor" >&2
  failed=1
fi

if [ -f "$native_executor" ]; then
  require_literal "$native_executor" 'private const val TOOL_IMPORT_RISK = "native_import_risk_summary"' 'native import risk typed tool is present'
  require_literal "$native_executor" 'private const val TOOL_STRINGS_CLUSTER = "native_strings_cluster"' 'native string clustering typed tool is present'
else
  printf 'Native executor missing: %s\n' "$native_executor" >&2
  failed=1
fi

if [ -f "$frida_executor" ] && [ -f "$frida_client" ]; then
  require_literal "$frida_executor" 'private const val TOOL_NETWORK_HINTS = "frida_network_hints"' 'Frida network hints typed tool is present'
  require_literal "$frida_client" 'net-hints' 'Frida bounded client exposes fixed network hints command'
else
  printf 'Frida executor or client missing for network-hints guard\n' >&2
  failed=1
fi

if [ -f "$tool_catalog" ]; then
  require_literal "$tool_catalog" '"runtimeTarget": "android_rootfs_only"' 'tool catalog declares Android/rootfs-only runtime target'
  require_literal "$tool_catalog" '"id": "android_pcap_start"' 'tool catalog matches runtime Android pcap start tool name'
  require_literal "$tool_catalog" '"confirmation": "dynamic_tool_authorization"' 'pcap catalog marks capture as dynamic-tool authorization only'
  require_literal "$tool_catalog" '"duration_seconds": "1..300"' 'pcap catalog bounds capture duration'
  require_literal "$tool_catalog" '"snaplen": "96..2048"' 'pcap catalog bounds snaplen'
  require_literal "$tool_catalog" '"max_bytes": "1MiB..64MiB"' 'pcap catalog bounds capture size'
  require_literal "$tool_catalog" '"disallowedInputs": [' 'pcap catalog declares forbidden capture inputs'
  require_literal "$tool_catalog" '"raw_filter"' 'pcap catalog forbids raw filter input'
  require_literal "$tool_catalog" '"mitm_enabled"' 'pcap catalog forbids MITM input'
  require_literal "$tool_catalog" '"output": "Bounded pcap capture session metadata; no VPN, no CA install, no Surfing config edit"' 'pcap catalog documents no VPN/CA/Surfing mutation'
  require_literal "$tool_catalog" '"id": "native_strings_cluster"' 'tool catalog includes native string clusters'
  require_literal "$tool_catalog" '"id": "native_import_risk_summary"' 'tool catalog includes native import risk summary'
  require_literal "$tool_catalog" '"id": "perfetto_target_stats"' 'tool catalog includes fixed Perfetto target stats'
  require_literal "$tool_catalog" '"id": "perfetto_capture"' 'tool catalog includes fixed Perfetto capture'
  require_literal "$tool_catalog" '"id": "android_tooling_status"' 'tool catalog includes Android tooling status'
  require_literal "$tool_catalog" '"id": "android_device_diagnostics"' 'tool catalog includes Android device diagnostics'
  require_literal "$tool_catalog" '"id": "rootfs_pcap_info"' 'tool catalog includes rootfs pcap info analysis'
  require_literal "$tool_catalog" '"id": "rootfs_pcap_protocol_summary"' 'tool catalog includes rootfs pcap protocol analysis'
  require_literal "$tool_catalog" '"id": "rootfs_pcap_dns_summary"' 'tool catalog includes rootfs pcap DNS analysis'
  require_literal "$tool_catalog" '"id": "rootfs_pcap_tls_summary"' 'tool catalog includes rootfs pcap TLS metadata analysis'
  require_literal "$tool_catalog" '"id": "rootfs_pcap_top_connections"' 'tool catalog includes rootfs pcap top connections analysis'
  require_literal "$tool_catalog" '"pathPolicy": "latest AutoCrack pcap metadata only; no model-supplied path' 'rootfs pcap catalog forbids model-supplied paths'
else
  printf 'Tool catalog missing: %s\n' "$tool_catalog" >&2
  failed=1
fi

if [ -f "$android_manifest" ] && [ -f "$foreground_service" ]; then
  require_literal "$android_manifest" 'android.permission.FOREGROUND_SERVICE_SPECIAL_USE' 'PTY foreground service declares specialUse permission'
  require_literal "$android_manifest" 'android:name=".runtime.PtySessionForegroundService"' 'PTY foreground service is declared in manifest'
  require_literal "$android_manifest" 'android:foregroundServiceType="specialUse"' 'PTY foreground service declares specialUse type'
  require_literal "$android_manifest" 'android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE' 'PTY foreground service documents specialUse subtype'
  require_literal "$foreground_service" 'ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)' 'PTY service uses typed AndroidX foreground-service API'
  require_literal "$foreground_service" '@SuppressLint("ForegroundServiceType")' 'ForegroundServiceType lint suppression is scoped and guarded by manifest checks'
else
  printf 'Foreground service guard input missing: %s or %s\n' "$android_manifest" "$foreground_service" >&2
  failed=1
fi


if [ -f "$dynamic_tool_catalog" ]; then
  require_literal "$dynamic_tool_catalog" '"id": "network_hints"' 'dynamic catalog includes fixed Frida network hints'
  require_literal "$dynamic_tool_catalog" '"id": "tls_trace"' 'dynamic catalog includes fixed Frida TLS trace'
  require_literal "$dynamic_tool_catalog" '"id": "net_detect_stack"' 'dynamic catalog includes fixed Frida network stack detection'
else
  printf 'Dynamic tool catalog missing: %s\n' "$dynamic_tool_catalog" >&2
  failed=1
fi

if [ "$failed" -ne 0 ]; then
  cat >&2 <<'EOF'

Final runtime target violation.
Agent-callable production tools must run on Android/rootfs only. GitHub Actions and
local ADB are allowed for build/debug workflows, but not inside app/src/main or
trusted toolpack runtime payloads.
EOF
  exit 1
fi

printf 'ANDROID_ROOTFS_ONLY_RUNTIME_CHECK_OK\n'
