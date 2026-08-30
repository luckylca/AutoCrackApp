package com.luckylca.autocrack.debug

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import com.luckylca.autocrack.root.ProcessRootCommandRunner
import com.luckylca.autocrack.root.RootDetector
import com.luckylca.autocrack.runtime.ChrootRuntimeEngine
import com.luckylca.autocrack.runtime.RootShellRuntimeEngine
import com.luckylca.autocrack.runtime.RuntimeLayout
import com.luckylca.autocrack.runtime.ToolpackPackageInstaller
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * Debug-only ADB-friendly installer for any built-in-trusted toolpack.
 *
 * Put the package at files/debug-validation/generic-toolpack.zip with run-as, then launch this
 * activity. ToolpackPackageInstaller still enforces the production BuiltInToolpackTrustPolicy.
 */
class DebugToolpackInstallActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread {
            val report = runCatching { runBlocking { installAndTest() } }.getOrElse { error ->
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

    private suspend fun installAndTest(): JSONObject {
        val packageFile = File(filesDir, TOOLPACK_INPUT_PATH)
        require(packageFile.isFile && packageFile.length() > 0L) {
            "Generic toolpack file is missing from private debug-validation input"
        }

        val expectedId = intent.getStringExtra("expected_id")?.trim().orEmpty()
        val expectedVersion = intent.getStringExtra("expected_version")?.trim().orEmpty()
        val layout = RuntimeLayout(applicationContext).initialize()
        val runner = ProcessRootCommandRunner()
        val root = RootDetector(runner).inspect()
        check(root.isRootGranted) { root.diagnostic ?: "Root not granted" }
        val suPath = requireNotNull(root.suPath) { "Root granted without su path" }
        val installer = ToolpackPackageInstaller(applicationContext, layout)
        val install = installer.install(Uri.fromFile(packageFile))

        if (expectedId.isNotBlank()) {
            check(install.manifest.id == expectedId) {
                "Unexpected toolpack id: ${install.manifest.id}; expected $expectedId"
            }
        }
        if (expectedVersion.isNotBlank()) {
            check(install.manifest.version == expectedVersion) {
                "Unexpected toolpack version: ${install.manifest.version}; expected $expectedVersion"
            }
        }

        val host = RootShellRuntimeEngine(layout, suPath)
        val chroot = ChrootRuntimeEngine(layout, host)
        val installed = installer.listInstalled().single {
            it.manifest.id == install.manifest.id && it.manifest.version == install.manifest.version
        }
        val selfTest = installer.runSelfTests(installed, chroot)

        return JSONObject()
            .put("success", selfTest.passed)
            .put("toolpackId", install.manifest.id)
            .put("toolpackVersion", install.manifest.version)
            .put("payloadBytes", install.payloadBytes)
            .put("installedPath", install.installedPath)
            .put("selfTestPassed", selfTest.passed)
            .put(
                "commands",
                JSONArray(install.manifest.commands.map { command -> command.name }),
            )
            .put(
                "selfTests",
                JSONArray(selfTest.results.map { result ->
                    JSONObject()
                        .put("id", result.test.id)
                        .put("passed", result.passed)
                        .put("exitCode", result.commandResult.exitCode ?: JSONObject.NULL)
                        .put("stdout", result.commandResult.stdout)
                        .put("stderr", result.commandResult.stderr)
                        .put("failure", result.failure ?: JSONObject.NULL)
                }),
            )
    }

    private companion object {
        const val TOOLPACK_INPUT_PATH = "debug-validation/generic-toolpack.zip"
        const val REPORT_PATH = "debug-validation/generic-toolpack-install-report.json"
    }
}
