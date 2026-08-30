package com.luckylca.autocrack.debug

import android.app.Activity
import android.os.Bundle
import com.luckylca.autocrack.agent.AgentToolSession
import com.luckylca.autocrack.agent.AgentToolSessionFactory
import com.luckylca.autocrack.apk.PackageRepository
import com.luckylca.autocrack.root.ProcessRootCommandRunner
import com.luckylca.autocrack.root.RootDetector
import com.luckylca.autocrack.runtime.ChrootRuntimeEngine
import com.luckylca.autocrack.runtime.AgentExecutionForegroundService
import com.luckylca.autocrack.runtime.RootShellRuntimeEngine
import com.luckylca.autocrack.runtime.RuntimeLayout
import com.luckylca.autocrack.runtime.ShellCommandRequest
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** Debug-only validation of the same AgentToolSessionFactory used by the production model button. */
class DebugProductionAgentToolsValidationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val targetPackage = intent.getStringExtra("package_name")?.trim().orEmpty().ifBlank { DEFAULT_TARGET_PACKAGE }
        val foregroundLeaseId = AgentExecutionForegroundService.acquire(
            context = applicationContext,
            conversationId = "debug-production-tools",
            label = targetPackage,
        )
        Thread {
            try {
                val report = runCatching { runBlocking { validateProductionTools() } }.getOrElse { error ->
                    JSONObject()
                        .put("success", false)
                        .put("failure", error.message ?: error::class.java.name)
                        .put("exception", error::class.java.name)
                }
                val output = File(filesDir, REPORT_PATH)
                output.parentFile?.mkdirs()
                output.writeText(report.toString(2), Charsets.UTF_8)
            } finally {
                AgentExecutionForegroundService.release(applicationContext, foregroundLeaseId)
                runOnUiThread { finish() }
            }
        }.start()
    }

    private suspend fun validateProductionTools(): JSONObject {
        val packageName = intent.getStringExtra("package_name")?.trim().orEmpty().ifBlank { DEFAULT_TARGET_PACKAGE }
        fun markStage(stage: String) {
            val output = File(filesDir, REPORT_PATH)
            output.parentFile?.mkdirs()
            output.writeText(
                JSONObject()
                    .put("success", false)
                    .put("stage", stage)
                    .put("timestampEpochMillis", System.currentTimeMillis())
                    .toString(2),
                Charsets.UTF_8,
            )
        }
        markStage("started")
        val layout = RuntimeLayout(applicationContext).initialize()
        val runner = ProcessRootCommandRunner()
        val detector = RootDetector(runner)
        val root = detector.inspect()
        check(root.isRootGranted) { root.diagnostic ?: "Root not granted" }
        val suPath = requireNotNull(root.suPath)
        markStage("root_ready")
        val host = RootShellRuntimeEngine(layout, suPath)
        val chroot = ChrootRuntimeEngine(layout, host)
        val repository = PackageRepository(applicationContext, runner)

        markStage("extract_ready")
        // Keep the selected target alive, but the static Agent session itself has no permission to launch it.
        host.execute(
            ShellCommandRequest(
                command = "monkey -p '$packageName' 1 >/dev/null 2>&1 || true",
                workingDirectory = layout.runtimeRoot.path,
                timeoutMillis = 10_000L,
            ),
        )
        delay(500)

        val extraction = repository.extractPackage(root, packageName)
        val factory = AgentToolSessionFactory(applicationContext, runner, detector)
        val events = JSONArray()
        val nativeExtraction = repository.extractPackage(root, AUTOCRACK_PACKAGE)

        suspend fun call(session: AgentToolSession, name: String, args: JSONObject = JSONObject()): JSONObject {
            val result = JSONObject(session.dispatch(name, args))
            check(result.optBoolean("ok")) { "$name returned ok=false: $result" }
            events.put(JSONObject().put("tool", name).put("ok", true).put("at", System.currentTimeMillis()))
            return result
        }

        markStage("static_session_create")
        val staticSession = factory.create(
            extraction,
            allowDynamicTools = false,
            knownRootStatus = root,
            onStage = { stage -> markStage("static_$stage") },
        )
        val staticToolNames = staticSession.tools.map { it.name }
        check("apk_jadx_class" in staticToolNames)
        check("apktool_decode_summary" in staticToolNames)
        check("apktool_smali_search" in staticToolNames)
        check("perfetto_capture" in staticToolNames)
        check("perfetto_target_stats" in staticToolNames)
        check(staticToolNames.none { it.startsWith("frida_") || it.startsWith("debugger_") })

        val jadx: JSONObject
        val apktool: JSONObject
        val smaliSearch: JSONObject
        val perfettoCapture: JSONObject
        val perfettoStats: JSONObject
        val launchRejected: Boolean
        try {
            markStage("before_apk_jadx_class")
            jadx = call(
                staticSession,
                "apk_jadx_class",
                JSONObject().put("class_name", "$packageName.MainActivity"),
            )
            markStage("after_apk_jadx_class")
            check(jadx.optString("source").contains("MainActivity")) { "JADX source did not contain MainActivity" }

            markStage("before_apktool_decode_summary")
            apktool = call(staticSession, "apktool_decode_summary")
            markStage("after_apktool_decode_summary")
            check(apktool.optLong("smaliFileCount") > 0L) { "Apktool returned no smali" }

            markStage("before_apktool_smali_search")
            smaliSearch = call(
                staticSession,
                "apktool_smali_search",
                JSONObject().put("query", "MainActivity").put("max_count", 8),
            )
            markStage("after_apktool_smali_search")

            launchRejected = runCatching {
                staticSession.dispatch(
                    "perfetto_capture",
                    JSONObject().put("duration_seconds", 1).put("launch_target", true),
                )
            }.isFailure
            check(launchRejected) { "Static-only Agent session unexpectedly launched the target" }

            markStage("before_perfetto_capture")
            perfettoCapture = call(
                staticSession,
                "perfetto_capture",
                JSONObject().put("duration_seconds", 1).put("launch_target", false),
            )
            markStage("after_perfetto_capture")
            check(perfettoCapture.optLong("traceBytes") > 0L) { "Perfetto captured no data" }
            markStage("before_perfetto_target_stats")
            perfettoStats = call(staticSession, "perfetto_target_stats")
            markStage("after_perfetto_target_stats")
            check(perfettoStats.optString("stats").contains("target_threads")) { "Perfetto target stats missing" }
        } finally {
            staticSession.closeSafely()
        }

        markStage("native_session_create")
        val nativeSession = factory.create(
            nativeExtraction,
            allowDynamicTools = false,
            knownRootStatus = root,
            onStage = { stage -> markStage("native_$stage") },
        )
        val nativeToolNames = nativeSession.tools.map { it.name }
        check("native_list_libraries" in nativeToolNames) { "Factory did not register native SO tools" }
        val nativeLibraries: JSONObject
        val nativeElfInfo: JSONObject
        val nativeJniMap: JSONObject
        val nativeFunctions: JSONObject
        val nativeReport: JSONObject
        try {
            markStage("before_native_list_libraries")
            nativeLibraries = call(nativeSession, "native_list_libraries")
            markStage("after_native_list_libraries")
            check(nativeLibraries.optInt("count") > 0) { "AutoCrack APK had no native libraries for validation" }
            val firstLibrary = nativeLibraries.getJSONArray("libraries").getJSONObject(0).getString("entry")
            markStage("before_native_elf_info")
            nativeElfInfo = call(nativeSession, "native_elf_info", JSONObject().put("library", firstLibrary))
            markStage("after_native_elf_info")
            check(
                nativeElfInfo.optString("machine").contains("AArch64", ignoreCase = true) ||
                    nativeElfInfo.optString("machine").contains("ARM", ignoreCase = true),
            ) { "Native ELF analysis did not identify ARM: $nativeElfInfo" }
            markStage("before_native_jni_map")
            nativeJniMap = call(nativeSession, "native_jni_map", JSONObject().put("library", firstLibrary))
            markStage("after_native_jni_map")
            markStage("before_native_rizin_functions")
            nativeFunctions = call(
                nativeSession,
                "native_rizin_functions",
                JSONObject().put("library", firstLibrary).put("max_count", 16),
            )
            markStage("after_native_rizin_functions")
            check(nativeFunctions.optInt("functionCountObserved") > 0) { "Rizin returned no functions" }
            markStage("before_native_rizin_report")
            nativeReport = call(nativeSession, "native_rizin_report", JSONObject().put("library", firstLibrary))
            markStage("after_native_rizin_report")
            check(nativeReport.getJSONObject("summary").getJSONObject("counts").optInt("functions") > 0) {
                "Rizin deep report had no functions"
            }
        } finally {
            nativeSession.closeSafely()
        }

        markStage("orphan_cleanup_probe")
        // Prove that a timed-out one-shot chroot command no longer leaves descendants behind.
        val timeoutResult = chroot.execute(
            ShellCommandRequest(
                command = "bash -c 'sleep 30'",
                workingDirectory = "/workspace",
                timeoutMillis = 500L,
            ),
        )
        check(timeoutResult.timedOut) { "Synthetic chroot timeout did not time out" }
        val orphanProbe = host.execute(
            ShellCommandRequest(
                command = """
                    set -eu
                    found=${'$'}(grep -a -l -F 'AUTOC_CHROOT_REQUEST_TOKEN=' /proc/[0-9]*/environ 2>/dev/null | wc -l)
                    printf 'TAGGED_PROCESSES=%s\n' "${'$'}found"
                """.trimIndent(),
                workingDirectory = layout.runtimeRoot.path,
                timeoutMillis = 5_000L,
            ),
        )
        check(orphanProbe.succeeded && orphanProbe.stdout.contains("TAGGED_PROCESSES=0")) {
            "Timed-out chroot descendants remain: ${orphanProbe.stdout} ${orphanProbe.stderr}"
        }

        markStage("dynamic_session_prepare")
        // Re-launch in case the OS reclaimed the sample while static tools were running.
        host.execute(
            ShellCommandRequest(
                command = "monkey -p '$packageName' 1 >/dev/null 2>&1 || true",
                workingDirectory = layout.runtimeRoot.path,
                timeoutMillis = 10_000L,
            ),
        )
        delay(500)

        markStage("dynamic_session_create")
        val dynamicSession = factory.create(
            extraction,
            allowDynamicTools = true,
            knownRootStatus = root,
            onStage = { stage -> markStage("dynamic_$stage") },
        )
        val dynamicToolNames = dynamicSession.tools.map { it.name }
        check("frida_start" in dynamicToolNames) { "Factory did not register Frida for the exact running target" }
        check("debugger_attach" in dynamicToolNames) { "Factory did not register LLDB for the exact running target" }
        try {
            call(dynamicSession, "frida_start")
            call(dynamicSession, "frida_ping")
            call(dynamicSession, "frida_stop")
            call(dynamicSession, "debugger_attach")
            call(dynamicSession, "debugger_detach")
        } finally {
            dynamicSession.closeSafely()
        }

        markStage("final_report")
        return JSONObject()
            .put("success", true)
            .put("packageName", packageName)
            .put("staticToolCount", staticToolNames.size)
            .put("nativeToolCount", nativeToolNames.size)
            .put("dynamicToolCount", dynamicToolNames.size)
            .put("staticToolNames", JSONArray(staticToolNames))
            .put("nativeToolNames", JSONArray(nativeToolNames))
            .put("dynamicToolNames", JSONArray(dynamicToolNames))
            .put("jadxBytes", jadx.optLong("bytes"))
            .put("apktoolSmaliFiles", apktool.optLong("smaliFileCount"))
            .put("smaliSearchMatches", smaliSearch.optInt("matchCount"))
            .put("perfettoTraceBytes", perfettoCapture.optLong("traceBytes"))
            .put("perfettoStats", perfettoStats.optString("stats"))
            .put("nativeLibraryCount", nativeLibraries.optInt("count"))
            .put("nativeFirstLibrary", nativeElfInfo.optString("entry"))
            .put("nativeJniStaticMappings", nativeJniMap.optInt("staticMappingCount"))
            .put("nativeRizinFunctions", nativeFunctions.optInt("functionCountObserved"))
            .put("nativeRizinReportFunctions", nativeReport.getJSONObject("summary").getJSONObject("counts").optInt("functions"))
            .put("launchRejectedWithoutDynamicPermission", launchRejected)
            .put("chrootTimeoutObserved", timeoutResult.timedOut)
            .put("chrootOrphanProbe", orphanProbe.stdout)
            .put("events", events)
    }

    private companion object {
        const val DEFAULT_TARGET_PACKAGE = "com.example.myapplication"
        const val AUTOCRACK_PACKAGE = "com.luckylca.autocrack"
        const val REPORT_PATH = "debug-validation/production-agent-tools-report.json"
    }
}
