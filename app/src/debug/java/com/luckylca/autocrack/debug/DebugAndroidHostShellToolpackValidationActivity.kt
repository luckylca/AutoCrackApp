package com.luckylca.autocrack.debug

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import com.luckylca.autocrack.agent.AgentToolSessionFactory
import com.luckylca.autocrack.agent.AndroidHostShellBridge
import com.luckylca.autocrack.agent.DangerousOperationDecision
import com.luckylca.autocrack.root.ProcessRootCommandRunner
import com.luckylca.autocrack.root.RootDetector
import com.luckylca.autocrack.runtime.ChrootRuntimeEngine
import com.luckylca.autocrack.runtime.RootShellRuntimeEngine
import com.luckylca.autocrack.runtime.RuntimeLayout
import com.luckylca.autocrack.runtime.ToolpackPackageInstaller
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** Debug-only real-device validation for the Android host root shell bridge toolpack. */
class DebugAndroidHostShellToolpackValidationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread {
            val report = runCatching { runBlocking { validate() } }.getOrElse { error ->
                JSONObject()
                    .put("success", false)
                    .put("failure", error.message ?: error::class.java.name)
                    .put("exception", error::class.java.name)
            }
            val output = File(filesDir, REPORT_RELATIVE_PATH)
            output.parentFile?.mkdirs()
            output.writeText(report.toString(2), Charsets.UTF_8)
            runOnUiThread { finish() }
        }.start()
    }

    private suspend fun validate(): JSONObject {
        val packageFile = File(filesDir, PACKAGE_RELATIVE_PATH)
        require(packageFile.isFile && packageFile.length() > 0L) { "android-host-shell toolpack file is missing" }
        val layout = RuntimeLayout(applicationContext).initialize()
        val runner = ProcessRootCommandRunner()
        val detector = RootDetector(runner)
        val root = detector.inspect()
        check(root.isRootGranted) { root.diagnostic ?: "Root not granted" }
        val suPath = requireNotNull(root.suPath) { "Root granted without su path" }
        val installer = ToolpackPackageInstaller(applicationContext, layout)
        val install = installer.install(Uri.fromFile(packageFile))
        check(install.manifest.id == AndroidHostShellBridge.TOOLPACK_ID)
        check(install.manifest.version == AndroidHostShellBridge.TOOLPACK_VERSION)

        val host = RootShellRuntimeEngine(layout, suPath)
        val chroot = ChrootRuntimeEngine(layout, host)
        val installed = installer.listInstalled().single {
            it.manifest.id == AndroidHostShellBridge.TOOLPACK_ID
        }
        val selfTest = installer.runSelfTests(installed, chroot)
        check(selfTest.passed) { "android-host-shell toolpack self-test failed" }

        val runtime = AgentToolSessionFactory(applicationContext, runner, detector).createMobileAgent(
            sessionId = "host-shell-smoke-${UUID.randomUUID()}",
            knownRootStatus = root,
            dangerousOperationGate = { DangerousOperationDecision.ALLOW_ONCE },
        )
        val result = try {
            JSONObject(
                runtime.tools.dispatch(
                    "exec_bash",
                    JSONObject().put(
                        "script",
                        """
                            set -eu
                            echo '=== ID ==='
                            android-shell id
                            echo '=== MODEL ==='
                            android-shell getprop ro.product.model
                            echo '=== PACKAGE ==='
                            android-shell pm list packages | grep '^package:com.ss.android.ugc.aweme$'
                            echo '=== PATH ==='
                            android-shell pm path com.ss.android.ugc.aweme
                            echo '=== HOST SHELL PIPELINE ==='
                            android-shell sh -c 'pm list packages | grep com.ss.android.ugc.aweme'
                            echo '=== SHARED WORKSPACE ==='
                            android-shell sh -c 'printf HOST_BRIDGE_WORKSPACE_OK > /workspace/bridge-host-file.txt'
                            cat /workspace/bridge-host-file.txt
                            android-shell sh -c 'test -f /workspace/bridge-host-file.txt && echo HOST_ABSOLUTE_WORKSPACE_OK'
                            rm -f /workspace/bridge-host-file.txt
                        """.trimIndent(),
                    ),
                ),
            )
        } finally {
            runtime.cancelAllCommands()
        }
        check(result.optBoolean("ok")) { "bridge smoke exec_bash failed: $result" }
        val stdout = result.getString("stdout")
        check(stdout.contains("uid=0(root)")) { "host shell was not root: $stdout" }
        check(stdout.contains("package:com.ss.android.ugc.aweme")) { "Douyin package was not visible through bridge" }
        check(stdout.contains("HOST_BRIDGE_WORKSPACE_OK")) { "host/debian workspace bridge failed" }

        return JSONObject()
            .put("success", true)
            .put("toolpackId", install.manifest.id)
            .put("toolpackVersion", install.manifest.version)
            .put("payloadBytes", install.payloadBytes)
            .put("installedPath", install.installedPath)
            .put("selfTestPassed", selfTest.passed)
            .put(
                "selfTests",
                JSONArray(selfTest.results.map { item ->
                    JSONObject()
                        .put("id", item.test.id)
                        .put("passed", item.passed)
                        .put("exitCode", item.commandResult.exitCode ?: JSONObject.NULL)
                        .put("failure", item.failure ?: JSONObject.NULL)
                }),
            )
            .put("bridgeResult", result)
    }

    private companion object {
        const val PACKAGE_RELATIVE_PATH = "debug-validation/android-host-shell-toolpack.zip"
        const val REPORT_RELATIVE_PATH = "debug-validation/android-host-shell-toolpack-report.json"
    }
}
