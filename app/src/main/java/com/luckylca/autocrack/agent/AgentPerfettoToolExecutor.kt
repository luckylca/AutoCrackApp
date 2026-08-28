package com.luckylca.autocrack.agent

import com.luckylca.autocrack.apk.PackageOutputParser
import com.luckylca.autocrack.runtime.ChrootRuntimeEngine
import com.luckylca.autocrack.runtime.RootShellRuntimeEngine
import com.luckylca.autocrack.runtime.RuntimeLayout
import com.luckylca.autocrack.runtime.ShellCommandRequest
import com.luckylca.autocrack.runtime.ShellEscaper
import java.io.File
import org.json.JSONObject

/** Bounded Android system tracing for one selected package plus fixed read-only PerfettoSQL. */
class AgentPerfettoToolExecutor(
    private val packageName: String,
    private val layout: RuntimeLayout,
    private val host: RootShellRuntimeEngine,
    private val chroot: ChrootRuntimeEngine,
    private val appUid: Int,
    private val allowTargetLaunch: Boolean,
) : AgentToolExecutor {
    override val tools: List<AgentToolDefinition> = buildDefinitions()
    private val workspace = layout.createRuntimeWorkspace().canonicalFile
    private val traceFile = File(workspace, TRACE_FILE).canonicalFile

    init {
        PackageOutputParser.requireValidPackageName(packageName)
        require(layout.isManagedPath(workspace) && layout.isManagedPath(traceFile)) {
            "Perfetto workspace escaped AutoCrack managed storage"
        }
    }

    override suspend fun dispatch(toolName: String, arguments: JSONObject): String {
        val result = when (toolName) {
            TOOL_CAPTURE -> capture(
                durationSeconds = arguments.optInt("duration_seconds", DEFAULT_DURATION_SECONDS)
                    .coerceIn(MIN_DURATION_SECONDS, MAX_DURATION_SECONDS),
                launchTarget = arguments.optBoolean("launch_target", false),
            )
            TOOL_TARGET_STATS -> targetStats()
            else -> error("Unknown or unauthorized Perfetto Agent tool: $toolName")
        }
        return result.put("ok", true).put("tool", toolName).put("packageName", packageName).toString()
    }

    private suspend fun capture(durationSeconds: Int, launchTarget: Boolean): JSONObject {
        require(!launchTarget || allowTargetLaunch) {
            "Launching the target is disabled for this Agent run; enable dynamic tools explicitly in the UI"
        }
        traceFile.delete()
        val systemTrace = "/data/misc/perfetto-traces/autocrack-agent-${System.currentTimeMillis()}.trace"
        val launch = if (launchTarget) {
            "monkey -p ${ShellEscaper.quote(packageName)} 1 >/dev/null 2>&1 || true\n"
        } else {
            ""
        }
        val command = """
            set -eu
            TRACE=${ShellEscaper.quote(systemTrace)}
            cleanup() { rm -f -- "${'$'}TRACE"; }
            trap cleanup EXIT HUP INT TERM
            $launch/system/bin/perfetto -o "${'$'}TRACE" -t ${durationSeconds}s \
              sched/sched_switch sched/sched_waking am wm gfx view binder_driver
            test -s "${'$'}TRACE"
            cp -- "${'$'}TRACE" ${ShellEscaper.quote(traceFile.path)}
            chown $appUid:$appUid ${ShellEscaper.quote(traceFile.path)}
            stat -c 'TRACE_BYTES=%s' ${ShellEscaper.quote(traceFile.path)}
        """.trimIndent()
        val execution = host.execute(
            ShellCommandRequest(
                command = command,
                workingDirectory = layout.runtimeRoot.path,
                timeoutMillis = (durationSeconds * 1_000L) + CAPTURE_OVERHEAD_MILLIS,
            ),
        )
        require(execution.succeeded) {
            execution.failure ?: execution.stderr.take(MAX_ERROR_CHARS).ifBlank { "Perfetto capture failed" }
        }
        require(traceFile.isFile && traceFile.length() > 0L) { "Perfetto trace is missing" }
        return JSONObject()
            .put("durationSeconds", durationSeconds)
            .put("launchTarget", launchTarget)
            .put("traceBytes", traceFile.length())
            .put("durationMillis", execution.durationMillis)
    }

    private suspend fun targetStats(): JSONObject {
        require(traceFile.isFile && traceFile.length() > 0L) { "Run perfetto_capture before querying target stats" }
        val sql = fixedTargetSql(packageName)
        val execution = chroot.execute(
            ShellCommandRequest(
                command = "trace_processor query /workspace/$TRACE_FILE ${ShellEscaper.quote(sql)}",
                workingDirectory = "/workspace",
                timeoutMillis = QUERY_TIMEOUT_MILLIS,
            ),
        )
        require(execution.succeeded) {
            execution.failure ?: execution.stderr.take(MAX_ERROR_CHARS).ifBlank { "Perfetto query failed" }
        }
        require(execution.stdout.contains("target_threads")) { "Perfetto output is missing target statistics" }
        return JSONObject()
            .put("traceBytes", traceFile.length())
            .put("durationMillis", execution.durationMillis)
            .put("stats", execution.stdout.take(MAX_QUERY_OUTPUT_CHARS))
            .put("stderr", execution.stderr.take(MAX_ERROR_CHARS))
    }

    private fun buildDefinitions(): List<AgentToolDefinition> {
        val duration = JSONObject().put("type", "integer")
            .put("description", "Trace duration in seconds")
            .put("minimum", MIN_DURATION_SECONDS).put("maximum", MAX_DURATION_SECONDS)
        val launch = JSONObject().put("type", "boolean")
            .put("description", "Whether AutoCrack should launch only the already-selected target app before tracing")
        return listOf(
            AgentToolDefinition(
                TOOL_CAPTURE,
                "Capture a 1-5 second Android system trace with a fixed safe set of scheduler/Binder/UI categories for the already-selected app. No arbitrary trace config or output path is accepted.",
                AgentJsonSchema.objectSchema(JSONObject().put("duration_seconds", duration).put("launch_target", launch)),
            ),
            AgentToolDefinition(
                TOOL_TARGET_STATS,
                "Run fixed read-only PerfettoSQL over the most recent AutoCrack trace and report scheduler/thread statistics for the already-selected package. Arbitrary SQL is unavailable.",
                AgentJsonSchema.emptyObject(),
            ),
        )
    }

    companion object {
        const val TOOLPACK_ID = "perfetto-analysis"
        const val TOOLPACK_VERSION = "perfetto-58.2-autocrack-1.0.0"
        private const val TOOL_CAPTURE = "perfetto_capture"
        private const val TOOL_TARGET_STATS = "perfetto_target_stats"
        private const val TRACE_FILE = "agent-perfetto.trace"
        private const val DEFAULT_DURATION_SECONDS = 2
        private const val MIN_DURATION_SECONDS = 1
        private const val MAX_DURATION_SECONDS = 5
        private const val CAPTURE_OVERHEAD_MILLIS = 15_000L
        private const val QUERY_TIMEOUT_MILLIS = 60_000L
        private const val MAX_QUERY_OUTPUT_CHARS = 20_000
        private const val MAX_ERROR_CHARS = 2_000

        internal fun fixedTargetSql(packageName: String): String {
            PackageOutputParser.requireValidPackageName(packageName)
            return """
                SELECT COUNT(*) AS sched_rows FROM sched;
                SELECT COUNT(*) AS target_threads
                FROM thread t JOIN process p USING(upid)
                WHERE p.name = '$packageName';
                SELECT COUNT(*) AS target_sched_rows
                FROM sched s JOIN thread t USING(utid) JOIN process p USING(upid)
                WHERE p.name = '$packageName';
                SELECT COALESCE(t.name, '[unnamed]') AS thread_name, COUNT(*) AS sched_rows
                FROM sched s JOIN thread t USING(utid) JOIN process p USING(upid)
                WHERE p.name = '$packageName'
                GROUP BY t.name ORDER BY sched_rows DESC LIMIT 16;
            """.trimIndent().replace("\n", " ")
        }
    }
}
