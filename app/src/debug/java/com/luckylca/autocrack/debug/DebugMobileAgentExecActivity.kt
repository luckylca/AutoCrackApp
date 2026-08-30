package com.luckylca.autocrack.debug

import android.app.Activity
import android.os.Bundle
import android.util.Base64
import com.luckylca.autocrack.agent.AgentToolSessionFactory
import com.luckylca.autocrack.agent.DangerousOperationDecision
import com.luckylca.autocrack.root.ProcessRootCommandRunner
import com.luckylca.autocrack.root.RootDetector
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** Debug-only ADB harness that exercises the production Mobile Agent exec_bash path. */
class DebugMobileAgentExecActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread {
            val report = runCatching { runBlocking { execute() } }.getOrElse { error ->
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

    private suspend fun execute(): JSONObject {
        val script = intent.getStringExtra("script")
            ?: intent.getStringExtra("script_b64")?.let { encoded ->
                String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
            }
            ?: error("Missing script or script_b64")
        val timeoutMs = intent.getLongExtra("timeout_ms", 30_000L)
        val cwd = intent.getStringExtra("cwd")?.trim().orEmpty().ifBlank { "/workspace" }
        val runner = ProcessRootCommandRunner()
        val detector = RootDetector(runner)
        val root = detector.inspect()
        check(root.isRootGranted) { root.diagnostic ?: "Root not granted" }
        val runtime = AgentToolSessionFactory(applicationContext, runner, detector).createMobileAgent(
            sessionId = "adb-mobile-agent-${UUID.randomUUID()}",
            knownRootStatus = root,
            dangerousOperationGate = { DangerousOperationDecision.ALLOW_ONCE },
        )
        val result = JSONObject(
            runtime.tools.dispatch(
                "exec_bash",
                JSONObject()
                    .put("script", script)
                    .put("cwd", cwd)
                    .put("timeout_ms", timeoutMs),
            ),
        )
        val cleanupCompleted = withTimeoutOrNull(5_000L) {
            runCatching { runtime.cleanupSessionProcesses() }.isSuccess
        } ?: false
        runtime.cancelAllCommands()
        return JSONObject()
            .put("success", result.optBoolean("ok"))
            .put("workspace", runtime.workspacePath)
            .put("installedToolpacks", runtime.installedToolpacks.size)
            .put("cleanupCompleted", cleanupCompleted)
            .put("result", result)
    }

    private companion object {
        const val REPORT_PATH = "debug-validation/mobile-agent-exec-report.json"
    }
}
