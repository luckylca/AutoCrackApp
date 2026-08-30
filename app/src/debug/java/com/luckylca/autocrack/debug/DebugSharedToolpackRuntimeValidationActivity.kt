package com.luckylca.autocrack.debug

import android.app.Activity
import android.os.Bundle
import com.luckylca.autocrack.agent.AgentToolSessionFactory
import com.luckylca.autocrack.agent.MobileAgentWorkspacePolicy
import com.luckylca.autocrack.root.ProcessRootCommandRunner
import com.luckylca.autocrack.root.RootDetector
import com.luckylca.autocrack.runtime.RuntimeLayout
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** Debug-only fixed runtime probes for the shared Mobile Pi Agent environment. */
class DebugSharedToolpackRuntimeValidationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread {
            val report = runCatching { runBlocking { validateRuntime() } }.getOrElse { error ->
                JSONObject()
                    .put("success", false)
                    .put("failure", error.message ?: error::class.java.name)
                    .put("exception", error::class.java.name)
            }
            val output = File(filesDir, REPORT_PATH)
            output.parentFile?.mkdirs()
            output.writeText(report.toString(2), Charsets.UTF_8)
            runOnUiThread { finish() }
        }.start()
    }

    private suspend fun validateRuntime(): JSONObject {
        val layout = RuntimeLayout(applicationContext).initialize()
        val runner = ProcessRootCommandRunner()
        val detector = RootDetector(runner)
        val root = detector.inspect()
        check(root.isRootGranted) { root.diagnostic ?: "Root not granted" }
        val sessionId = "debug-shared-runtime"
        MobileAgentWorkspacePolicy.markIsolated(layout.createAgentWorkspace(sessionId))
        val runtime = AgentToolSessionFactory(applicationContext, runner, detector).createMobileAgent(
            sessionId = sessionId,
            knownRootStatus = root,
        )

        val probes = listOf(
            Probe(
                id = "shared-environment",
                timeoutMillis = 30_000L,
                command = """
                    set -eu
                    printf 'ANDROID_SHELL=%s\n' "${'$'}(command -v android-shell)"
                    printf 'TCPDUMP=%s\n' "${'$'}(command -v tcpdump)"
                    printf 'FRIDA=%s\n' "${'$'}(command -v frida)"
                    printf 'FRIDA_PS=%s\n' "${'$'}(command -v frida-ps)"
                    printf 'FRIDA_SERVER_CTL=%s\n' "${'$'}(command -v android-frida-server)"
                    android-shell id
                    tcpdump --version
                    python3 -c 'import frida; print("PYTHON_FRIDA=" + frida.__version__)'
                    frida --version
                """.trimIndent(),
            ),
            Probe(
                id = "frida-server-and-processes",
                timeoutMillis = 45_000L,
                command = """
                    set -eu
                    android-frida-server start
                    android-frida-server status
                    frida-ps -H 127.0.0.1:27042 | head -n 20
                """.trimIndent(),
            ),
            Probe(
                id = "tcpdump-arbitrary-bpf",
                timeoutMillis = 45_000L,
                command = """
                    set -eu
                    rm -f /workspace/tcpdump-validation.pcap /tmp/tcpdump-validation.out /tmp/tcpdump-validation.err
                    host_pcap=/workspace/tcpdump-validation.pcap
                    AUTOC_TCPDUMP_TIMEOUT_MS=20000 tcpdump -i any -nn -s0 -c 1 -w "${'$'}host_pcap" 'tcp port 27042' >/tmp/tcpdump-validation.out 2>/tmp/tcpdump-validation.err &
                    cap_pid=${'$'}!
                    sleep 1
                    frida-ps -H 127.0.0.1:27042 >/tmp/frida-ps-during-capture.txt
                    wait "${'$'}cap_pid"
                    test -s /workspace/tcpdump-validation.pcap
                    printf 'PCAP_BYTES=%s\n' "${'$'}(wc -c < /workspace/tcpdump-validation.pcap)"
                    cat /tmp/tcpdump-validation.out || true
                    cat /tmp/tcpdump-validation.err >&2 || true
                    tcpdump -nn -r "${'$'}host_pcap" -c 1 'tcp port 27042'
                """.trimIndent(),
            ),
            Probe(
                id = "frida-owned-fixture-java",
                timeoutMillis = 45_000L,
                command = """
                    set -eu
                    android-frida-server start >/tmp/frida-server-start.json
                    trap 'android-frida-server stop >/dev/null 2>&1 || true' EXIT
                    android-shell am start -n com.luckylca.autocrack/.debug.DebugFridaFixtureActivity >/dev/null
                    sleep 1
                    pid="${'$'}(android-shell pidof 'com.luckylca.autocrack:frida_fixture' | tr -d '\r\n')"
                    test -n "${'$'}pid"
                    printf 'FIXTURE_PID=%s\n' "${'$'}pid"
                    frida -H 127.0.0.1:27042 -p "${'$'}pid" -q -e 'console.log("AUTOCRACK_PROCESS_ID=" + Process.id)'
                    frida -H 127.0.0.1:27042 -p "${'$'}pid" -q -e 'Java.perform(function () { console.log("AUTOCRACK_JAVA_AVAILABLE=" + Java.available); })'
                """.trimIndent(),
            ),
        )

        val results = JSONArray()
        var allPassed = true
        try {
            for (probe in probes) {
                val result = JSONObject(
                    runtime.tools.dispatch(
                        "exec_bash",
                        JSONObject()
                            .put("script", probe.command)
                            .put("cwd", "/workspace")
                            .put("timeout_ms", probe.timeoutMillis),
                    ),
                )
                val passed = result.optBoolean("ok")
                allPassed = allPassed && passed
                results.put(
                    JSONObject()
                        .put("id", probe.id)
                        .put("passed", passed)
                        .put("exitCode", result.opt("exitCode") ?: JSONObject.NULL)
                        .put("timedOut", result.optBoolean("timedOut"))
                        .put("stdout", result.optString("stdout"))
                        .put("stderr", result.optString("stderr"))
                        .put("failure", result.opt("failure") ?: JSONObject.NULL),
                )
            }
        } finally {
            runCatching { runtime.cleanupSessionProcesses() }
            runtime.cancelAllCommands()
        }

        return JSONObject()
            .put("success", allPassed)
            .put("probes", results)
    }

    private data class Probe(
        val id: String,
        val command: String,
        val timeoutMillis: Long,
    )

    private companion object {
        const val REPORT_PATH = "debug-validation/shared-toolpack-runtime-report.json"
    }
}
