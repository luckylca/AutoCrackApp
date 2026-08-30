package com.luckylca.autocrack.debug

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import com.luckylca.autocrack.root.ProcessRootCommandRunner
import com.luckylca.autocrack.root.RootDetector
import com.luckylca.autocrack.root.RootToolCommandFactory
import com.luckylca.autocrack.runtime.ChrootRuntimeEngine
import com.luckylca.autocrack.runtime.HostFridaSessionManager
import com.luckylca.autocrack.runtime.RootShellRuntimeEngine
import com.luckylca.autocrack.runtime.RuntimeLayout
import com.luckylca.autocrack.runtime.ShellCommandRequest
import com.luckylca.autocrack.runtime.ToolpackPackageInstaller
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** Debug-only installer validation using the real ToolpackPackageInstaller and self-test path. */
class DebugFridaToolpackInstallActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread {
            val report = runCatching { runBlocking { installAndTest() } }.getOrElse { error ->
                JSONObject()
                    .put("success", false)
                    .put("failure", error.message ?: error::class.java.name)
                    .put("exception", error::class.java.name)
            }
            val output = File(filesDir, "debug-validation/frida-toolpack-install-report.json")
            output.parentFile?.mkdirs()
            output.writeText(report.toString(2), Charsets.UTF_8)
            runOnUiThread { finish() }
        }.start()
    }

    private suspend fun installAndTest(): JSONObject {
        val packageFile = File(filesDir, "debug-validation/android-frida-toolpack.zip")
        require(packageFile.isFile && packageFile.length() > 0) { "Frida toolpack file is missing" }
        val layout = RuntimeLayout(applicationContext).initialize()
        val runner = ProcessRootCommandRunner()
        val root = RootDetector(runner).inspect()
        check(root.isRootGranted) { root.diagnostic ?: "Root not granted" }
        val suPath = requireNotNull(root.suPath) { "Root granted without su path" }
        val packsRoot = File(layout.rootfsRoot, "opt/autocrack/toolpacks/packs/android-frida")
        val oldRoot = File(packsRoot, OLD_TOOLPACK_VERSION)
        val quotedOldRoot = RootToolCommandFactory.shellQuote(oldRoot.path)
        val quotedPacksRoot = RootToolCommandFactory.shellQuote(packsRoot.path)
        val cleanupScript = """
            old_root=$quotedOldRoot
            packs_root=$quotedPacksRoot
            for cache in "${'$'}old_root"/python/*/__pycache__; do
              [ -e "${'$'}cache" ] || continue
              rm -rf -- "${'$'}cache"
            done
            for backup in "${'$'}packs_root"/${OLD_TOOLPACK_VERSION}.backup-*; do
              [ -e "${'$'}backup" ] || continue
              rm -rf -- "${'$'}backup"
            done
        """.trimIndent()
        val cleanup = runner.run(
            command = listOf(suPath, "-c", cleanupScript),
            label = "Clean Frida 1.0.0 bytecode leftovers",
            timeoutMillis = 10_000L,
        )
        check(cleanup.succeeded) { cleanup.failure ?: cleanup.stderr.ifBlank { "Frida migration cleanup failed" } }

        val installer = ToolpackPackageInstaller(applicationContext, layout)
        val install = installer.install(Uri.fromFile(packageFile))
        check(install.manifest.id == HostFridaSessionManager.TOOLPACK_ID)
        check(install.manifest.version == HostFridaSessionManager.TOOLPACK_VERSION)
        val host = RootShellRuntimeEngine(layout, requireNotNull(root.suPath))
        val chroot = ChrootRuntimeEngine(layout, host)
        val installed = installer.listInstalled().single {
            it.manifest.id == HostFridaSessionManager.TOOLPACK_ID &&
                it.manifest.version == HostFridaSessionManager.TOOLPACK_VERSION
        }
        val selfTest = installer.runSelfTests(installed, chroot)
        check(selfTest.passed) { "Frida toolpack self-test failed" }
        val version = HostFridaSessionManager.TOOLPACK_VERSION
        val serverPath = "/opt/autocrack/toolpacks/packs/android-frida/$version/bin/frida-server-android"
        val pathProbe = chroot.execute(
            ShellCommandRequest(
                command = "ls -ld '$serverPath'; stat '$serverPath'; if [ -L '$serverPath' ]; then echo IS_SYMLINK=true; else echo IS_SYMLINK=false; fi; sha256sum '$serverPath'",
                workingDirectory = "/workspace",
                timeoutMillis = 10_000L,
            ),
        )
        val results = JSONArray(selfTest.results.map { result ->
            JSONObject()
                .put("id", result.test.id)
                .put("passed", result.passed)
                .put("exitCode", result.commandResult.exitCode ?: JSONObject.NULL)
                .put("failure", result.failure ?: JSONObject.NULL)
        })
        return JSONObject()
            .put("success", true)
            .put("toolpackId", install.manifest.id)
            .put("toolpackVersion", install.manifest.version)
            .put("payloadBytes", install.payloadBytes)
            .put("installedPath", install.installedPath)
            .put("selfTestPassed", selfTest.passed)
            .put("pathProbeExitCode", pathProbe.exitCode ?: JSONObject.NULL)
            .put("pathProbeStdout", pathProbe.stdout)
            .put("pathProbeStderr", pathProbe.stderr)
            .put("selfTests", results)
    }

    private companion object {
        const val OLD_TOOLPACK_VERSION = "frida-17.17.0-autocrack-1.0.4"
    }
}
