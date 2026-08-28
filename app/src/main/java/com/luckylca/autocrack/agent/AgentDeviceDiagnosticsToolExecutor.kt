package com.luckylca.autocrack.agent

import android.os.Process
import com.luckylca.autocrack.root.RootStatus
import com.luckylca.autocrack.runtime.RootShellRuntimeEngine
import com.luckylca.autocrack.runtime.RuntimeLayout
import com.luckylca.autocrack.runtime.ShellCommandRequest
import com.luckylca.autocrack.runtime.ShellEscaper
import java.io.File
import org.json.JSONObject

/** Read-only device/rootfs diagnostics for Android/rootfs-only Agent readiness. */
class AgentDeviceDiagnosticsToolExecutor(
    private val rootStatus: RootStatus,
    private val host: RootShellRuntimeEngine,
    private val layout: RuntimeLayout,
    private val appUid: Int = Process.myUid(),
) : AgentToolExecutor {
    override val tools: List<AgentToolDefinition> = listOf(
        AgentToolDefinition(
            TOOL_DEVICE_DIAGNOSTICS,
            "Read Android/rootfs readiness diagnostics: Root provider, SELinux, rootfs state, managed runtime paths, and loopback helper ports. Read-only only.",
            AgentJsonSchema.emptyObject(),
        ),
        AgentToolDefinition(
            TOOL_TOOLING_STATUS,
            "Read availability of Android-side tooling such as toybox, ip, ss, tcpdump, perfetto, logcat, settings, cmd, dumpsys and managed tcpdump. Read-only only.",
            AgentJsonSchema.emptyObject(),
        ),
    )

    override suspend fun dispatch(toolName: String, arguments: JSONObject): String {
        require(arguments.length() == 0) { "$toolName does not accept arguments" }
        val result = when (toolName) {
            TOOL_DEVICE_DIAGNOSTICS -> rootReport("device_diagnostics", deviceDiagnosticsScript(), 8_000L)
            TOOL_TOOLING_STATUS -> rootReport("tooling_status", toolingStatusScript(), 8_000L)
            else -> error("Unknown or unauthorized device diagnostics Agent tool: $toolName")
        }
        return result
            .put("ok", true)
            .put("tool", toolName)
            .put("runtimeTarget", "android_rootfs_only")
            .toString()
    }

    private suspend fun rootReport(operation: String, script: String, timeoutMillis: Long): JSONObject {
        val execution = host.execute(
            ShellCommandRequest(
                command = script,
                workingDirectory = "/",
                timeoutMillis = timeoutMillis,
            ),
        )
        return JSONObject()
            .put("operation", operation)
            .put("commandSucceeded", execution.succeeded)
            .put("exitCode", execution.exitCode ?: JSONObject.NULL)
            .put("timedOut", execution.timedOut)
            .put("cancelled", execution.cancelled)
            .put("failure", execution.failure ?: JSONObject.NULL)
            .put("stdout", execution.stdout.take(MAX_RETAINED_TEXT))
            .put("stderr", execution.stderr.take(MAX_RETAINED_TEXT))
            .put("stdoutTruncated", execution.stdoutTruncated || execution.stdout.length > MAX_RETAINED_TEXT)
            .put("stderrTruncated", execution.stderrTruncated || execution.stderr.length > MAX_RETAINED_TEXT)
            .put("auditFile", execution.auditFilePath)
            .put("rootProvider", rootStatus.provider.name)
            .put("rootAccessState", rootStatus.accessState.name)
            .put("appUid", appUid)
            .put("rootfsState", layout.readRootfsState().name)
            .put("rootfsVersion", layout.readRootfsVersion() ?: JSONObject.NULL)
    }

    private fun deviceDiagnosticsScript(): String {
        val managedTcpdump = ShellEscaper.quote(File(layout.binRoot, "tcpdump").canonicalFile.path)
        val rootfsRoot = ShellEscaper.quote(layout.rootfsRoot.path)
        val runtimeRoot = ShellEscaper.quote(layout.runtimeRoot.path)
        val provider = ShellEscaper.quote(rootStatus.provider.name)
        val access = ShellEscaper.quote(rootStatus.accessState.name)
        return """
            set -u
            printf 'schema=android_device_diagnostics_v1\n'
            printf 'mode=read_only\n'
            printf 'root_provider=%s\n' $provider
            printf 'root_access_state=%s\n' $access
            printf 'managed_tcpdump=%s\n' $managedTcpdump
            printf 'runtime_root=%s\n' $runtimeRoot
            printf 'rootfs_root=%s\n' $rootfsRoot
            printf 'section=identity\n'
            id 2>/dev/null || true
            printf 'section=selinux\n'
            getenforce 2>/dev/null || true
            printf 'section=kernel\n'
            uname -a 2>/dev/null || true
            printf 'section=rootfs\n'
            [ -d $rootfsRoot ] && echo rootfs_dir=present || echo rootfs_dir=missing
            [ -x $managedTcpdump ] && echo managed_tcpdump_executable=true || echo managed_tcpdump_executable=false
            printf 'section=loopback_ports\n'
            awk 'NR>1 && (${ '$' }2 ~ /0100007F:(6972|13AF)/) {print}' /proc/net/tcp 2>/dev/null | head -n 16 || true
            exit 0
        """.trimIndent()
    }

    private fun toolingStatusScript(): String {
        val managedTcpdump = ShellEscaper.quote(File(layout.binRoot, "tcpdump").canonicalFile.path)
        return """
            set -u
            printf 'schema=android_tooling_status_v1\n'
            printf 'mode=read_only\n'
            printf 'section=commands\n'
            for name in su toybox busybox ip ss tcpdump perfetto logcat settings cmd dumpsys getprop; do
              found=${ '$' }(command -v "${ '$' }name" 2>/dev/null || true)
              if [ -n "${ '$' }found" ]; then printf 'command\t%s\tpresent\t%s\n' "${ '$' }name" "${ '$' }found"; else printf 'command\t%s\tmissing\t\n' "${ '$' }name"; fi
            done
            printf 'section=managed_tcpdump\n'
            if [ -x $managedTcpdump ]; then
              bytes=${ '$' }(wc -c < $managedTcpdump 2>/dev/null || echo 0)
              sha=${ '$' }(sha256sum $managedTcpdump 2>/dev/null | awk '{print ${ '$' }1}' || echo unavailable)
              printf 'managed_tcpdump\tpresent\t%s\t%s\n' "${ '$' }bytes" "${ '$' }sha"
            else
              printf 'managed_tcpdump\tmissing\t0\tunavailable\n'
            fi
            printf 'section=proxy_modules\n'
            for path in /data/adb/box /data/adb/box_bll /data/adb/modules; do [ -e "${ '$' }path" ] && printf 'path\t%s\tpresent\n' "${ '$' }path" || true; done
            exit 0
        """.trimIndent()
    }

    companion object {
        const val TOOL_DEVICE_DIAGNOSTICS = "android_device_diagnostics"
        const val TOOL_TOOLING_STATUS = "android_tooling_status"
        val DIAGNOSTIC_TOOL_NAMES = listOf(TOOL_DEVICE_DIAGNOSTICS, TOOL_TOOLING_STATUS)
        private const val MAX_RETAINED_TEXT = 24_000
    }
}
