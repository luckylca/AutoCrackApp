package com.luckylca.autocrack.debug

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.os.Process
import com.luckylca.autocrack.root.ProcessRootCommandRunner
import com.luckylca.autocrack.root.RootDetector
import com.luckylca.autocrack.runtime.ChrootRuntimeEngine
import com.luckylca.autocrack.runtime.RootShellRuntimeEngine
import com.luckylca.autocrack.runtime.RuntimeLayout
import com.luckylca.autocrack.runtime.ShellCommandRequest
import com.luckylca.autocrack.runtime.ToolpackPackageInstaller
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** Debug-only real-device validation for the trusted Perfetto trace_processor toolpack. */
class DebugPerfettoToolpackValidationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread {
            val report = runCatching { runBlocking { installAndValidate() } }.getOrElse { error ->
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

    private suspend fun installAndValidate(): JSONObject {
        val packageFile = File(filesDir, TOOLPACK_INPUT_PATH)
        require(packageFile.isFile && packageFile.length() > 0L) { "Perfetto analysis toolpack file is missing" }

        val layout = RuntimeLayout(applicationContext).initialize()
        val runner = ProcessRootCommandRunner()
        val root = RootDetector(runner).inspect()
        check(root.isRootGranted) { root.diagnostic ?: "Root not granted" }
        val suPath = requireNotNull(root.suPath) { "Root granted without su path" }
        val installer = ToolpackPackageInstaller(applicationContext, layout)
        val install = installer.install(Uri.fromFile(packageFile))
        check(install.manifest.id == TOOLPACK_ID) { "Unexpected toolpack id: ${install.manifest.id}" }
        check(install.manifest.version == TOOLPACK_VERSION) { "Unexpected toolpack version: ${install.manifest.version}" }

        val host = RootShellRuntimeEngine(layout, suPath)
        val chroot = ChrootRuntimeEngine(layout, host)
        val installed = installer.listInstalled().single {
            it.manifest.id == TOOLPACK_ID && it.manifest.version == TOOLPACK_VERSION
        }
        val selfTest = installer.runSelfTests(installed, chroot)
        check(selfTest.passed) { "Perfetto trace_processor self-test failed" }

        val targetPackage = intent.getStringExtra("package_name")?.trim().orEmpty().ifBlank { DEFAULT_TARGET_PACKAGE }
        val targetLaunch = host.execute(
            ShellCommandRequest(
                command = "monkey -p '$targetPackage' 1 >/dev/null 2>&1 || true",
                workingDirectory = layout.runtimeRoot.path,
                timeoutMillis = 10_000L,
            ),
        )
        check(targetLaunch.succeeded) { targetLaunch.failure ?: "Target launch command failed" }

        val runtimeWorkspace = layout.createRuntimeWorkspace()
        val workspaceTrace = File(runtimeWorkspace, TRACE_FILE).canonicalFile
        workspaceTrace.delete()
        val systemTracePath = "/data/misc/perfetto-traces/$SYSTEM_TRACE_FILE"
        val capture = host.execute(
            ShellCommandRequest(
                command = """
                    set -eu
                    rm -f '$systemTracePath'
                    /system/bin/perfetto -o '$systemTracePath' -t 2s \
                      sched/sched_switch sched/sched_waking am wm gfx view binder_driver
                    test -s '$systemTracePath'
                    cp '$systemTracePath' '${workspaceTrace.path}'
                    chown ${Process.myUid()}:${Process.myUid()} '${workspaceTrace.path}'
                    stat -c 'TRACE_BYTES=%s' '${workspaceTrace.path}'
                """.trimIndent(),
                workingDirectory = layout.runtimeRoot.path,
                timeoutMillis = 20_000L,
            ),
        )
        check(capture.succeeded) { capture.failure ?: capture.stderr.ifBlank { "Perfetto capture failed" } }
        check(workspaceTrace.isFile && workspaceTrace.length() > 0L) { "Captured trace is missing from workspace" }

        val querySql = """
            SELECT COUNT(*) AS sched_rows FROM sched;
            SELECT COUNT(*) AS target_threads
            FROM thread t JOIN process p USING(upid)
            WHERE p.name = '$targetPackage';
            SELECT COUNT(*) AS target_sched_rows
            FROM sched s JOIN thread t USING(utid) JOIN process p USING(upid)
            WHERE p.name = '$targetPackage';
        """.trimIndent().replace("\n", " ")
        val query = chroot.execute(
            ShellCommandRequest(
                command = "trace_processor query /workspace/$TRACE_FILE \"$querySql\"",
                workingDirectory = "/workspace",
                timeoutMillis = 60_000L,
            ),
        )
        check(query.succeeded) { query.failure ?: query.stderr.ifBlank { "trace_processor query failed" } }
        check(query.stdout.contains("sched_rows")) { "trace_processor output did not contain sched_rows" }

        return JSONObject()
            .put("success", true)
            .put("toolpackId", install.manifest.id)
            .put("toolpackVersion", install.manifest.version)
            .put("payloadBytes", install.payloadBytes)
            .put("selfTestPassed", selfTest.passed)
            .put("systemPerfettoVersion", SYSTEM_PERFETTO_VERSION_OBSERVED)
            .put("targetPackage", targetPackage)
            .put("traceBytes", workspaceTrace.length())
            .put("tracePath", workspaceTrace.path)
            .put("queryStdout", query.stdout)
            .put("queryStderr", query.stderr)
            .put(
                "selfTests",
                JSONArray(selfTest.results.map { result ->
                    JSONObject()
                        .put("id", result.test.id)
                        .put("passed", result.passed)
                        .put("exitCode", result.commandResult.exitCode ?: JSONObject.NULL)
                        .put("failure", result.failure ?: JSONObject.NULL)
                }),
            )
    }

    private companion object {
        const val TOOLPACK_ID = "perfetto-analysis"
        const val TOOLPACK_VERSION = "perfetto-58.2-autocrack-1.0.0"
        const val DEFAULT_TARGET_PACKAGE = "com.example.myapplication"
        const val TOOLPACK_INPUT_PATH = "debug-validation/perfetto-analysis-toolpack.zip"
        const val REPORT_PATH = "debug-validation/perfetto-analysis-report.json"
        const val TRACE_FILE = "perfetto-validation.trace"
        const val SYSTEM_TRACE_FILE = "autocrack-agent-validation.trace"
        const val SYSTEM_PERFETTO_VERSION_OBSERVED = "49.0"
    }
}
