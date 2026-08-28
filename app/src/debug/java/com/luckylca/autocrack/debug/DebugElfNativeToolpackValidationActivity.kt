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

/** Debug-only real-device proof for trusted ELF native toolpack install and self-test. */
class DebugElfNativeToolpackValidationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread {
            val report = runCatching { runBlocking { validate() } }.getOrElse { error ->
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

    private suspend fun validate(): JSONObject {
        val packageFile = File(filesDir, TOOLPACK_INPUT_PATH)
        require(packageFile.isFile && packageFile.length() > 0L) { "ELF native toolpack file is missing" }
        val layout = RuntimeLayout(applicationContext).initialize()
        val runner = ProcessRootCommandRunner()
        val root = RootDetector(runner).inspect()
        check(root.isRootGranted) { root.diagnostic ?: "Root not granted" }
        val host = RootShellRuntimeEngine(layout, requireNotNull(root.suPath))
        val chroot = ChrootRuntimeEngine(layout, host)
        val installer = ToolpackPackageInstaller(applicationContext, layout)
        val install = installer.install(Uri.fromFile(packageFile))
        check(install.manifest.id == TOOLPACK_ID) { "Unexpected toolpack id: ${install.manifest.id}" }
        check(install.manifest.version == TOOLPACK_VERSION) { "Unexpected toolpack version: ${install.manifest.version}" }
        val installed = installer.listInstalled().single {
            it.manifest.id == TOOLPACK_ID && it.manifest.version == TOOLPACK_VERSION
        }
        val selfTest = installer.runSelfTests(installed, chroot)
        check(selfTest.passed) { "ELF native toolpack self-test failed" }
        return JSONObject()
            .put("success", true)
            .put("toolpackId", install.manifest.id)
            .put("toolpackVersion", install.manifest.version)
            .put("payloadBytes", install.payloadBytes)
            .put("selfTestPassed", selfTest.passed)
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
        const val TOOLPACK_ID = "elf-native-static"
        const val TOOLPACK_VERSION = "checksec-3.2.0_autocrack-1.0.0"
        const val TOOLPACK_INPUT_PATH = "debug-validation/elf-native-static-toolpack.zip"
        const val REPORT_PATH = "debug-validation/elf-native-toolpack-report.json"
    }
}
