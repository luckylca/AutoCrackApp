package com.luckylca.autocrack.agent

import com.luckylca.autocrack.apk.PackageOutputParser
import android.os.Process
import com.luckylca.autocrack.runtime.RootShellRuntimeEngine
import com.luckylca.autocrack.runtime.RuntimeLayout
import com.luckylca.autocrack.runtime.ShellCommandRequest
import com.luckylca.autocrack.runtime.ShellCommandResult
import com.luckylca.autocrack.runtime.ShellEscaper
import com.luckylca.autocrack.runtime.ShellOutputMode
import java.io.File
import org.json.JSONObject

/**
 * Read-only Android/rootfs-only network reconnaissance scoped to the selected package.
 *
 * Capture support is intentionally metadata-first pcap only: it does not edit Surfing/box configs,
 * install CA certificates, enable VPNService, perform MITM, or expose arbitrary shell to the model.
 */
class AgentAndroidNetworkToolExecutor(
    private val packageName: String,
    private val host: RootShellRuntimeEngine,
    private val layout: RuntimeLayout,
    private val appUid: Int = Process.myUid(),
    private val allowCapture: Boolean = false,
) : AgentToolExecutor {
    private val captureRoot: File = File(
        layout.createRuntimeWorkspace(),
        "network-captures/${packageName.replace('.', '_')}",
    ).canonicalFile
    private val pcapPidFile: File = File(captureRoot, "tcpdump.pid").canonicalFile
    private val pcapMetaFile: File = File(captureRoot, "tcpdump-session.json").canonicalFile

    override val tools: List<AgentToolDefinition> = buildList {
        add(
            AgentToolDefinition(
                name = TOOL_SURFING_STATUS,
                description = "Read-only detection of Surfing/box proxy module paths, status files and config fingerprints on this Android device. Does not edit proxy rules.",
                parameters = AgentJsonSchema.emptyObject(),
            ),
        )
        add(
            AgentToolDefinition(
                name = TOOL_NET_ENVIRONMENT,
                description = "Read Android network interfaces, routes and system proxy settings from the device itself. Does not use VPN or desktop tools.",
                parameters = AgentJsonSchema.emptyObject(),
            ),
        )
        add(
            AgentToolDefinition(
                name = TOOL_TARGET_CONNECTIONS,
                description = "Resolve the selected package UID and list matching /proc/net TCP/UDP socket rows for endpoint correlation.",
                parameters = AgentJsonSchema.emptyObject(),
            ),
        )
        if (allowCapture) {
            add(
                AgentToolDefinition(
                    name = TOOL_PCAP_START,
                    description = "Start a bounded root tcpdump capture to the selected package workspace for later DNS/TLS/SNI/QUIC correlation. Does not use VPN, MITM, CA installation, or Surfing config edits.",
                    parameters = AgentJsonSchema.objectSchema(
                        properties = JSONObject()
                            .put("duration_seconds", JSONObject()
                                .put("type", "integer")
                                .put("minimum", 1)
                                .put("maximum", MAX_CAPTURE_SECONDS)
                                .put("description", "Bounded capture duration. Defaults to 30 seconds."))
                            .put("snaplen", JSONObject()
                                .put("type", "integer")
                                .put("minimum", 96)
                                .put("maximum", MAX_SNAPLEN)
                                .put("description", "Tcpdump snapshot length. Defaults to 256 bytes for metadata-first capture."))
                            .put("max_bytes", JSONObject()
                                .put("type", "integer")
                                .put("minimum", MIN_CAPTURE_BYTES)
                                .put("maximum", MAX_CAPTURE_BYTES)
                                .put("description", "Hard capture-size budget. Defaults to 16 MiB and is capped at 64 MiB.")),
                    ),
                ),
            )
            add(
                AgentToolDefinition(
                    name = TOOL_PCAP_STATUS,
                    description = "Inspect the current or last AutoCrack tcpdump capture state, including PID, output path and pcap size. Does not alter Surfing or proxy rules.",
                    parameters = AgentJsonSchema.emptyObject(),
                ),
            )
            add(
                AgentToolDefinition(
                    name = TOOL_PCAP_STOP,
                    description = "Stop only the verified AutoCrack-owned tcpdump helper for this selected package workspace. Does not touch Surfing or other proxy processes.",
                    parameters = AgentJsonSchema.emptyObject(),
                ),
            )
        }
    }

    init {
        PackageOutputParser.requireValidPackageName(packageName)
        require(layout.isManagedPath(captureRoot) && layout.isManagedPath(pcapPidFile) && layout.isManagedPath(pcapMetaFile)) {
            "Network capture workspace escaped AutoCrack managed storage"
        }
    }

    override suspend fun dispatch(toolName: String, arguments: JSONObject): String {
        val result = when (toolName) {
            TOOL_SURFING_STATUS -> {
                require(arguments.length() == 0) { "$toolName does not accept arguments" }
                rootReport("surfing_status", surfingStatusScript(), 8_000L)
            }
            TOOL_NET_ENVIRONMENT -> {
                require(arguments.length() == 0) { "$toolName does not accept arguments" }
                rootReport("net_environment", netEnvironmentScript(), 8_000L)
            }
            TOOL_TARGET_CONNECTIONS -> {
                require(arguments.length() == 0) { "$toolName does not accept arguments" }
                rootReport("target_connections", targetConnectionsScript(), 10_000L)
            }
            TOOL_PCAP_START -> {
                require(allowCapture) { "Packet capture requires explicit dynamic-tool authorization" }
                pcapStart(arguments)
            }
            TOOL_PCAP_STATUS -> {
                require(allowCapture) { "Packet capture requires explicit dynamic-tool authorization" }
                require(arguments.length() == 0) { "$toolName does not accept arguments" }
                pcapStatus()
            }
            TOOL_PCAP_STOP -> {
                require(allowCapture) { "Packet capture requires explicit dynamic-tool authorization" }
                require(arguments.length() == 0) { "$toolName does not accept arguments" }
                pcapStop()
            }
            else -> error("Unknown or unauthorized Android network Agent tool: $toolName")
        }
        return result
            .put("ok", true)
            .put("tool", toolName)
            .put("packageName", packageName)
            .put("runtimeTarget", "android_rootfs_only")
            .toString()
    }

    private suspend fun rootReport(
        operation: String,
        script: String,
        timeoutMillis: Long,
        outputMode: ShellOutputMode = ShellOutputMode.CAPTURE,
    ): JSONObject {
        val result = host.execute(
            ShellCommandRequest(
                command = script,
                workingDirectory = "/",
                timeoutMillis = timeoutMillis,
                outputMode = outputMode,
            ),
        )
        return JSONObject()
            .put("operation", operation)
            .put("commandSucceeded", result.succeeded)
            .put("exitCode", result.exitCode ?: JSONObject.NULL)
            .put("timedOut", result.timedOut)
            .put("cancelled", result.cancelled)
            .put("failure", result.failure ?: JSONObject.NULL)
            .put("durationMillis", result.durationMillis)
            .put("stdout", result.stdout.take(MAX_RETAINED_TEXT))
            .put("stderr", result.stderr.take(MAX_RETAINED_TEXT))
            .put("stdoutTruncated", result.stdoutTruncated || result.stdout.length > MAX_RETAINED_TEXT)
            .put("stderrTruncated", result.stderrTruncated || result.stderr.length > MAX_RETAINED_TEXT)
            .put("auditFile", result.auditFilePath)
    }

    private suspend fun pcapStart(arguments: JSONObject): JSONObject {
        requireKnownArguments(TOOL_PCAP_START, arguments, PCAP_START_ARGUMENT_KEYS)
        val durationSeconds = arguments.optInt("duration_seconds", DEFAULT_CAPTURE_SECONDS)
            .coerceIn(1, MAX_CAPTURE_SECONDS)
        val snaplen = arguments.optInt("snaplen", DEFAULT_SNAPLEN)
            .coerceIn(96, MAX_SNAPLEN)
        val maxBytes = arguments.optLong("max_bytes", DEFAULT_CAPTURE_BYTES)
            .coerceIn(MIN_CAPTURE_BYTES, MAX_CAPTURE_BYTES)
        captureRoot.mkdirs()
        val timestamp = System.currentTimeMillis()
        val pcapFile = File(captureRoot, "capture-$timestamp.pcap").canonicalFile
        require(layout.isManagedPath(pcapFile)) { "PCAP output escaped AutoCrack managed storage" }
        return rootReport(
            operation = "pcap_start",
            script = pcapStartScript(
                durationSeconds = durationSeconds,
                snaplen = snaplen,
                maxBytes = maxBytes,
                pcapPath = pcapFile.path,
                pidPath = pcapPidFile.path,
                metaPath = pcapMetaFile.path,
            ),
            timeoutMillis = 8_000L,
            outputMode = ShellOutputMode.CAPTURE,
        ).put("pcapPath", pcapFile.path)
            .put("requestedDurationSeconds", durationSeconds)
            .put("snaplen", snaplen)
            .put("maxBytes", maxBytes)
            .put("surfingConfigTouched", false)
            .put("vpnUsed", false)
            .put("httpsDecrypted", false)
    }

    private suspend fun pcapStatus(): JSONObject = rootReport(
        operation = "pcap_status",
        script = pcapStatusScript(pcapPidFile.path, pcapMetaFile.path),
        timeoutMillis = 6_000L,
    )

    private suspend fun pcapStop(): JSONObject = rootReport(
        operation = "pcap_stop",
        script = pcapStopScript(pcapPidFile.path, pcapMetaFile.path),
        timeoutMillis = 8_000L,
    ).put("surfingConfigTouched", false)
        .put("vpnUsed", false)

    private fun surfingStatusScript(): String = """
        set -u
        printf 'schema=android_surfing_status_v1\n'
        printf 'mode=read_only\n'
        printf 'vpn_used=false\n'
        printf 'section=known_paths\n'
        for path in \
          /data/adb/box_bll \
          /data/adb/box \
          /data/adb/box_bll/scripts/box.config \
          /data/adb/box/scripts/box.config \
          /data/adb/box_bll/clash/config.yaml \
          /data/adb/box/clash/config.yaml \
          /data/adb/box_bll/run \
          /data/adb/box/run
        do
          kind=missing
          readable=false
          bytes=0
          sha256=unavailable
          if [ -d "${'$'}path" ]; then kind=dir; fi
          if [ -f "${'$'}path" ]; then kind=file; fi
          if [ -r "${'$'}path" ]; then readable=true; fi
          if [ -f "${'$'}path" ]; then
            bytes=${'$'}(wc -c < "${'$'}path" 2>/dev/null || printf '0')
            sha256=${'$'}(sha256sum "${'$'}path" 2>/dev/null | awk '{print ${'$'}1}' || printf 'unavailable')
          fi
          printf 'path\t%s\t%s\t%s\t%s\t%s\n' "${'$'}path" "${'$'}kind" "${'$'}readable" "${'$'}bytes" "${'$'}sha256"
        done
        printf 'section=modules\n'
        for module in /data/adb/modules/*; do
          [ -e "${'$'}module" ] || continue
          name=${'$'}{module##*/}
          lower=${'$'}(printf '%s' "${'$'}name" | tr 'A-Z' 'a-z')
          case "${'$'}lower" in
            *surfing*|*box*|*clash*|*mihomo*|*sing-box*|*singbox*)
              state=present
              [ -f "${'$'}module/disable" ] && state=disabled
              printf 'module\t%s\t%s\n' "${'$'}name" "${'$'}state"
              ;;
          esac
        done
        printf 'section=processes\n'
        ps -A -o PID,UID,NAME,ARGS 2>/dev/null | grep -Ei 'surfing|box|mihomo|clash|sing-box|xray|v2ray|hysteria' | head -n 64 || true
        exit 0
    """.trimIndent()

    private fun netEnvironmentScript(): String = """
        set -u
        printf 'schema=android_net_environment_v1\n'
        printf 'vpn_used=false\n'
        printf 'section=proxy_settings\n'
        printf 'http_proxy='; settings get global http_proxy 2>/dev/null || true
        printf 'global_http_proxy_host='; settings get global global_http_proxy_host 2>/dev/null || true
        printf 'global_http_proxy_port='; settings get global global_http_proxy_port 2>/dev/null || true
        printf 'section=interfaces\n'
        if command -v ip >/dev/null 2>&1; then
          ip -o addr show 2>/dev/null | head -n 128 || true
        else
          ifconfig 2>/dev/null | head -n 128 || true
        fi
        printf 'section=routes_v4\n'
        ip route show 2>/dev/null | head -n 128 || true
        printf 'section=routes_v6\n'
        ip -6 route show 2>/dev/null | head -n 128 || true
        printf 'section=dns_props\n'
        getprop 2>/dev/null | grep -Ei 'dns|net\.dns|private_dns' | head -n 128 || true
        exit 0
    """.trimIndent()

    private fun targetConnectionsScript(): String {
        val pkg = ShellEscaper.quote(packageName)
        return """
            set -u
            package_name=$pkg
            uid_value=''
            line=${'$'}(cmd package list packages -U "${'$'}package_name" 2>/dev/null | head -n 1 || true)
            uid_value=${'$'}(printf '%s\n' "${'$'}line" | sed -n 's/.*uid:\([0-9][0-9]*\).*/\1/p' | head -n 1)
            if [ -z "${'$'}uid_value" ]; then
              uid_value=${'$'}(dumpsys package "${'$'}package_name" 2>/dev/null | sed -n 's/.*userId=\([0-9][0-9]*\).*/\1/p' | head -n 1)
            fi
            printf 'schema=android_target_connections_v1\n'
            printf 'package=%s\n' "${'$'}package_name"
            printf 'uid=%s\n' "${'$'}uid_value"
            printf 'vpn_used=false\n'
            printf 'section=proc_net_rows\n'
            if [ -n "${'$'}uid_value" ]; then
              printf 'proto\tlocal_hex\tremote_hex\tstate\tuid\tinode\n'
              for spec in tcp:/proc/net/tcp tcp6:/proc/net/tcp6 udp:/proc/net/udp udp6:/proc/net/udp6; do
                proto=${'$'}{spec%%:*}
                table=${'$'}{spec#*:}
                [ -r "${'$'}table" ] || continue
                awk -v proto="${'$'}proto" -v target_uid="${'$'}uid_value" 'NR > 1 && ${'$'}8 == target_uid { printf "%s\t%s\t%s\t%s\t%s\t%s\n", proto, ${'$'}2, ${'$'}3, ${'$'}4, ${'$'}8, ${'$'}10 }' "${'$'}table"
              done | head -n 256
            fi
            printf 'section=ss_snapshot\n'
            ss -tunap 2>/dev/null | grep -F "uid:${'$'}uid_value" | head -n 128 || true
            exit 0
        """.trimIndent()
    }

    private fun pcapStartScript(
        durationSeconds: Int,
        snaplen: Int,
        maxBytes: Long,
        pcapPath: String,
        pidPath: String,
        metaPath: String,
    ): String {
        val qPcap = ShellEscaper.quote(pcapPath)
        val qPid = ShellEscaper.quote(pidPath)
        val qMeta = ShellEscaper.quote(metaPath)
        val qPackage = ShellEscaper.quote(packageName)
        val qManagedTcpdump = ShellEscaper.quote(File(layout.binRoot, "tcpdump").canonicalFile.path)
        return """
            set -u
            mkdir -p ${ShellEscaper.quote(captureRoot.path)} || exit 20
            if [ -s $qPid ] && [ -f $qMeta ]; then
              old_pid=${'$'}(cat $qPid 2>/dev/null | tr -cd '0-9')
              old_pcap=${'$'}(sed -n 's/.*"pcapPath":"\([^"]*\)".*/\1/p' $qMeta | head -n 1)
              if [ -n "${'$'}old_pid" ] && [ -n "${'$'}old_pcap" ] && [ -d "/proc/${'$'}old_pid" ]; then
                old_cmd=${'$'}(tr '\000' ' ' < "/proc/${'$'}old_pid/cmdline" 2>/dev/null || true)
                case "${'$'}old_cmd" in
                  *tcpdump*"${'$'}old_pcap"*)
                    echo 'PCAP_ALREADY_RUNNING'
                    echo "pid=${'$'}old_pid"
                    echo "pcap=${'$'}old_pcap"
                    exit 0
                    ;;
                esac
              fi
            fi
            tcpdump_bin=''
            for candidate in $qManagedTcpdump /system/bin/tcpdump /system/xbin/tcpdump /vendor/bin/tcpdump /data/adb/ksu/bin/tcpdump /data/adb/magisk/tcpdump; do
              [ -x "${'$'}candidate" ] && { tcpdump_bin="${'$'}candidate"; break; }
            done
            if [ -z "${'$'}tcpdump_bin" ]; then
              tcpdump_bin=${'$'}(command -v tcpdump 2>/dev/null || true)
            fi
            [ -n "${'$'}tcpdump_bin" ] || { echo 'TCPDUMP_NOT_FOUND' >&2; exit 3; }
            rm -f $qPcap $qPid $qMeta
            (
              file_blocks=${'$'}((($maxBytes + 511) / 512))
              ulimit -f "${'$'}file_blocks" 2>/dev/null || true
              "${'$'}tcpdump_bin" -i any -s $snaplen -U -w $qPcap not port 27042 >/dev/null 2>&1 &
              child=${'$'}!
              started_ms=${'$'}(date +%s000)
              printf '%s\n' "${'$'}child" > $qPid
              printf '{"schemaVersion":1,"packageName":"%s","pid":%s,"pcapPath":"%s","durationSeconds":%s,"snaplen":%s,"maxBytes":%s,"vpnUsed":false,"surfingConfigTouched":false,"httpsDecrypted":false,"state":"running","startedAtEpochMillis":%s}\n' $qPackage "${'$'}child" "$pcapPath" "$durationSeconds" "$snaplen" "$maxBytes" "${'$'}started_ms" > $qMeta
              chown $appUid:$appUid $qPcap $qPid $qMeta 2>/dev/null || true
              chmod 0600 $qPcap $qPid $qMeta 2>/dev/null || true
              elapsed=0
              completion=duration_limit
              while [ "${'$'}elapsed" -lt $durationSeconds ] && kill -0 "${'$'}child" 2>/dev/null; do
                if [ -f $qPcap ]; then
                  bytes=${'$'}(wc -c < $qPcap 2>/dev/null || printf '0')
                  if [ "${'$'}bytes" -ge $maxBytes ]; then
                    completion=size_limit
                    kill -TERM "${'$'}child" 2>/dev/null || true
                    break
                  fi
                fi
                sleep 1
                elapsed=${'$'}((elapsed + 1))
              done
              if kill -0 "${'$'}child" 2>/dev/null; then
                kill -TERM "${'$'}child" 2>/dev/null || true
              elif [ "${'$'}elapsed" -lt $durationSeconds ]; then
                completion=helper_exit
              fi
              wait "${'$'}child" 2>/dev/null || true
              bytes=${'$'}(wc -c < $qPcap 2>/dev/null || printf '0')
              finished_ms=${'$'}(date +%s000)
              printf '{"schemaVersion":1,"packageName":"%s","pid":%s,"pcapPath":"%s","durationSeconds":%s,"snaplen":%s,"maxBytes":%s,"vpnUsed":false,"surfingConfigTouched":false,"httpsDecrypted":false,"state":"finished","completionReason":"%s","pcapBytes":%s,"startedAtEpochMillis":%s,"finishedAtEpochMillis":%s}\n' $qPackage "${'$'}child" "$pcapPath" "$durationSeconds" "$snaplen" "$maxBytes" "${'$'}completion" "${'$'}bytes" "${'$'}started_ms" "${'$'}finished_ms" > $qMeta
              rm -f $qPid 2>/dev/null || true
              chown $appUid:$appUid $qPcap $qMeta 2>/dev/null || true
              chmod 0600 $qPcap $qMeta 2>/dev/null || true
            ) >/dev/null 2>&1 &
            helper=${'$'}!
            echo "PCAP_CAPTURE_STARTED"
            echo "helperPid=${'$'}helper"
            echo "pcap=$pcapPath"
            echo "durationSeconds=$durationSeconds"
            echo "snaplen=$snaplen"
            echo "maxBytes=$maxBytes"
            exit 0
        """.trimIndent()
    }

    private fun pcapStatusScript(pidPath: String, metaPath: String): String {
        val qPid = ShellEscaper.quote(pidPath)
        val qMeta = ShellEscaper.quote(metaPath)
        return """
            set -u
            echo 'schema=android_pcap_status_v1'
            echo 'vpn_used=false'
            echo 'surfing_config_touched=false'
            if [ -f $qMeta ]; then
              echo 'section=meta'
              cat $qMeta 2>/dev/null || true
            else
              echo 'meta=missing'
            fi
            if [ -s $qPid ]; then
              pid=${'$'}(cat $qPid 2>/dev/null | tr -cd '0-9')
              echo "pid=${'$'}pid"
              if [ -n "${'$'}pid" ] && [ -d "/proc/${'$'}pid" ]; then
                echo 'state=running_or_reaping'
                printf 'cmdline='; tr '\000' ' ' < "/proc/${'$'}pid/cmdline" 2>/dev/null || true; printf '\n'
              else
                echo 'state=not_running'
              fi
            else
              echo 'pid=missing'
              echo 'state=not_running'
            fi
            if [ -f $qMeta ]; then
              pcap=${'$'}(sed -n 's/.*"pcapPath":"\([^"]*\)".*/\1/p' $qMeta | head -n 1)
              if [ -n "${'$'}pcap" ] && [ -f "${'$'}pcap" ]; then
                bytes=${'$'}(wc -c < "${'$'}pcap" 2>/dev/null || echo 0)
                echo "pcap=${'$'}pcap"
                echo "pcapBytes=${'$'}bytes"
              fi
            fi
            exit 0
        """.trimIndent()
    }

    private fun pcapStopScript(pidPath: String, metaPath: String): String {
        val qPid = ShellEscaper.quote(pidPath)
        val qMeta = ShellEscaper.quote(metaPath)
        return """
            set -u
            echo 'schema=android_pcap_stop_v1'
            echo 'vpn_used=false'
            echo 'surfing_config_touched=false'
            stopped=false
            pcap=''
            if [ -f $qMeta ]; then
              pcap=${'$'}(sed -n 's/.*"pcapPath":"\([^"]*\)".*/\1/p' $qMeta | head -n 1)
            fi
            if [ -s $qPid ]; then
              pid=${'$'}(cat $qPid 2>/dev/null | tr -cd '0-9')
              if [ -n "${'$'}pid" ] && [ -n "${'$'}pcap" ] && [ -d "/proc/${'$'}pid" ]; then
                cmdline=${'$'}(tr '\000' ' ' < "/proc/${'$'}pid/cmdline" 2>/dev/null || true)
                case "${'$'}cmdline" in
                  *tcpdump*"${'$'}pcap"*)
                    kill -TERM "${'$'}pid" 2>/dev/null || true
                    stopped=true
                    ;;
                esac
              fi
              rm -f $qPid 2>/dev/null || true
            fi
            if [ -f $qMeta ]; then
              if [ -n "${'$'}pcap" ] && [ -f "${'$'}pcap" ]; then
                chown $appUid:$appUid "${'$'}pcap" 2>/dev/null || true
                chmod 0600 "${'$'}pcap" 2>/dev/null || true
                bytes=${'$'}(wc -c < "${'$'}pcap" 2>/dev/null || echo 0)
                echo "pcap=${'$'}pcap"
                echo "pcapBytes=${'$'}bytes"
              fi
            fi
            echo "stopped=${'$'}stopped"
            exit 0
        """.trimIndent()
    }

    internal companion object {
        const val RUNTIME_TARGET = "android_rootfs_only"
        const val HTTPS_STRATEGY = "metadata_and_fixed_frida_templates_first_no_default_mitm"
        const val TOOL_SURFING_STATUS = "android_surfing_status"
        const val TOOL_NET_ENVIRONMENT = "android_net_environment"
        const val TOOL_TARGET_CONNECTIONS = "android_net_target_connections"
        const val TOOL_PCAP_START = "android_pcap_start"
        const val TOOL_PCAP_STATUS = "android_pcap_status"
        const val TOOL_PCAP_STOP = "android_pcap_stop"
        val NETWORK_TOOL_NAMES = listOf(
            TOOL_SURFING_STATUS,
            TOOL_NET_ENVIRONMENT,
            TOOL_TARGET_CONNECTIONS,
            TOOL_PCAP_START,
            TOOL_PCAP_STATUS,
            TOOL_PCAP_STOP,
        )
        val PCAP_START_ARGUMENT_KEYS = setOf("duration_seconds", "snaplen", "max_bytes")
        const val DEFAULT_CAPTURE_SECONDS = 30
        const val MAX_CAPTURE_SECONDS = 300
        const val DEFAULT_SNAPLEN = 256
        const val MAX_SNAPLEN = 2_048
        const val MIN_CAPTURE_BYTES = 1L * 1024L * 1024L
        const val DEFAULT_CAPTURE_BYTES = 16L * 1024L * 1024L
        const val MAX_CAPTURE_BYTES = 64L * 1024L * 1024L
        const val MAX_RETAINED_TEXT = 32_000

        fun requireKnownArguments(toolName: String, arguments: JSONObject, allowedKeys: Set<String>) {
            val unknown = arguments.keys().asSequence().filterNot(allowedKeys::contains).toList().sorted()
            require(unknown.isEmpty()) { "$toolName rejected unsupported argument(s): ${unknown.joinToString(", ")}" }
        }
    }
}
