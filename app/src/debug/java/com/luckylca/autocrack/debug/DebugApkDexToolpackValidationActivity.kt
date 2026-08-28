package com.luckylca.autocrack.debug

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.os.Process
import com.luckylca.autocrack.apk.ApkArtifactKind
import com.luckylca.autocrack.apk.PackageRepository
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

/** Debug-only real-device validation for the trusted JADX + Apktool toolpack. */
class DebugApkDexToolpackValidationActivity : Activity() {
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
        markStage("started")
        val packageFile = File(filesDir, TOOLPACK_INPUT_PATH)
        require(packageFile.isFile && packageFile.length() > 0L) { "APK/DEX static toolpack file is missing" }

        val layout = RuntimeLayout(applicationContext).initialize()
        val runner = ProcessRootCommandRunner()
        val root = RootDetector(runner).inspect()
        check(root.isRootGranted) { root.diagnostic ?: "Root not granted" }
        val suPath = requireNotNull(root.suPath) { "Root granted without su path" }
        val installer = ToolpackPackageInstaller(applicationContext, layout)

        val install = installer.install(Uri.fromFile(packageFile))
        check(install.manifest.id == TOOLPACK_ID) { "Unexpected toolpack id: ${install.manifest.id}" }
        check(install.manifest.version == TOOLPACK_VERSION) { "Unexpected toolpack version: ${install.manifest.version}" }
        markStage("installed")

        val host = RootShellRuntimeEngine(layout, suPath)
        val chroot = ChrootRuntimeEngine(layout, host)
        val orphanCleanup = host.execute(
            ShellCommandRequest(
                command = """
                    for pid in ${'$'}(ps -A -o PID,NAME | awk '${'$'}2 == "java" { print ${'$'}1 }'); do
                      cmd=${'$'}(tr '\\000' ' ' < /proc/${'$'}pid/cmdline 2>/dev/null || true)
                      case "${'$'}cmd" in
                        *jadx.cli.JadxCLI*apk-dex-sample-base.apk*) kill -TERM "${'$'}pid" 2>/dev/null || true ;;
                      esac
                    done
                """.trimIndent(),
                workingDirectory = layout.runtimeRoot.path,
                timeoutMillis = 5_000L,
            ),
        )
        check(orphanCleanup.succeeded) { orphanCleanup.failure ?: "Failed to clean stale JADX validation process" }
        markStage("orphan_cleanup_done")
        val installed = installer.listInstalled().single {
            it.manifest.id == TOOLPACK_ID && it.manifest.version == TOOLPACK_VERSION
        }
        val selfTest = installer.runSelfTests(installed, chroot)
        check(selfTest.passed) { "JADX/Apktool self-test failed" }
        markStage("self_test_passed")

        val targetPackage = intent.getStringExtra("package_name")?.trim().orEmpty().ifBlank { DEFAULT_TARGET_PACKAGE }
        val repository = PackageRepository(applicationContext, runner)
        val extraction = repository.extractPackage(root, targetPackage)
        val base = extraction.artifacts.singleOrNull { it.kind == ApkArtifactKind.BASE }
            ?: error("Target package has no extracted base APK")
        markStage("target_extracted")

        val runtimeWorkspace = layout.createRuntimeWorkspace()
        val sample = File(runtimeWorkspace, SAMPLE_APK).canonicalFile
        val jadxOut = File(runtimeWorkspace, JADX_OUTPUT).canonicalFile
        val apktoolOut = File(runtimeWorkspace, APKTOOL_OUTPUT).canonicalFile
        sample.delete()
        jadxOut.deleteRecursively()
        apktoolOut.deleteRecursively()
        File(base.localPath).copyTo(sample, overwrite = true)

        markStage("sample_ready")
        val jadxExecution = chroot.execute(
            ShellCommandRequest(
                command = """
                    set -eu
                    rm -rf -- /workspace/$JADX_OUTPUT
                    mkdir -p /workspace/$JADX_OUTPUT
                    export JADX_OPTS='-Xms64M -Xmx512M -XX:ActiveProcessorCount=2'
                    jadx --threads-count 2 --no-res \
                      --single-class com.example.myapplication.MainActivity \
                      --single-class-output /workspace/$JADX_OUTPUT/MainActivity.java \
                      /workspace/$SAMPLE_APK
                    test -s /workspace/$JADX_OUTPUT/MainActivity.java
                    java_count=${'$'}(find /workspace/$JADX_OUTPUT -type f -name '*.java' | wc -l)
                    printf 'JADX_JAVA_FILES=%s\n' "${'$'}java_count"
                    chown -R ${Process.myUid()}:${Process.myUid()} /workspace/$JADX_OUTPUT
                """.trimIndent(),
                workingDirectory = "/workspace",
                timeoutMillis = 180_000L,
            ),
        )
        check(jadxExecution.succeeded) {
            jadxExecution.failure ?: jadxExecution.stderr.ifBlank { "JADX real APK validation failed" }
        }
        val javaCount = findMetric(jadxExecution.stdout, "JADX_JAVA_FILES")
        markStage("jadx_passed:$javaCount")

        val apktoolExecution = chroot.execute(
            ShellCommandRequest(
                command = """
                    set -eu
                    rm -rf -- /workspace/$APKTOOL_OUTPUT
                    apktool decode --force --output /workspace/$APKTOOL_OUTPUT /workspace/$SAMPLE_APK
                    test -f /workspace/$APKTOOL_OUTPUT/AndroidManifest.xml
                    smali_count=${'$'}(find /workspace/$APKTOOL_OUTPUT -type f -name '*.smali' | wc -l)
                    manifest_bytes=${'$'}(wc -c < /workspace/$APKTOOL_OUTPUT/AndroidManifest.xml)
                    printf 'APKTOOL_SMALI_FILES=%s\nAPKTOOL_MANIFEST_BYTES=%s\n' "${'$'}smali_count" "${'$'}manifest_bytes"
                    chown -R ${Process.myUid()}:${Process.myUid()} /workspace/$APKTOOL_OUTPUT
                """.trimIndent(),
                workingDirectory = "/workspace",
                timeoutMillis = 180_000L,
            ),
        )
        check(apktoolExecution.succeeded) {
            apktoolExecution.failure ?: apktoolExecution.stderr.ifBlank { "Apktool real APK validation failed" }
        }
        val smaliCount = findMetric(apktoolExecution.stdout, "APKTOOL_SMALI_FILES")
        val manifestBytes = findMetric(apktoolExecution.stdout, "APKTOOL_MANIFEST_BYTES")
        markStage("apktool_passed:$smaliCount:$manifestBytes")
        check(javaCount > 0L) { "JADX produced no Java files" }
        check(smaliCount > 0L) { "Apktool produced no smali files" }
        check(manifestBytes > 0L) { "Apktool produced an empty AndroidManifest.xml" }
        check(jadxOut.isDirectory && apktoolOut.isDirectory) { "Static-analysis output is not visible to the app workspace" }

        return JSONObject()
            .put("success", true)
            .put("toolpackId", install.manifest.id)
            .put("toolpackVersion", install.manifest.version)
            .put("payloadBytes", install.payloadBytes)
            .put("targetPackage", targetPackage)
            .put("targetBaseApkSha256", base.sha256)
            .put("selfTestPassed", selfTest.passed)
            .put("javaFileCount", javaCount)
            .put("smaliFileCount", smaliCount)
            .put("decodedManifestBytes", manifestBytes)
            .put("jadxOutputPath", jadxOut.path)
            .put("apktoolOutputPath", apktoolOut.path)
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

    private fun markStage(stage: String) {
        val file = File(filesDir, STAGE_PATH)
        file.parentFile?.mkdirs()
        file.writeText("${System.currentTimeMillis()} $stage\n", Charsets.UTF_8)
    }

    private fun findMetric(stdout: String, name: String): Long = stdout.lineSequence()
        .firstOrNull { it.startsWith("$name=") }
        ?.substringAfter('=')
        ?.trim()
        ?.toLongOrNull()
        ?: error("Missing numeric metric: $name")

    private companion object {
        const val TOOLPACK_ID = "apk-dex-static"
        const val TOOLPACK_VERSION = "jadx-1.5.6_apktool-3.0.3"
        const val DEFAULT_TARGET_PACKAGE = "com.example.myapplication"
        const val TOOLPACK_INPUT_PATH = "debug-validation/apk-dex-static-toolpack.zip"
        const val REPORT_PATH = "debug-validation/apk-dex-static-report.json"
        const val STAGE_PATH = "debug-validation/apk-dex-stage.txt"
        const val SAMPLE_APK = "apk-dex-sample-base.apk"
        const val JADX_OUTPUT = "apk-dex-jadx-output"
        const val APKTOOL_OUTPUT = "apk-dex-apktool-output"
    }
}
