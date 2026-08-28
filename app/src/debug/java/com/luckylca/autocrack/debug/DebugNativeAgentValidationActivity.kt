package com.luckylca.autocrack.debug

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import com.luckylca.autocrack.agent.AgentToolSessionFactory
import com.luckylca.autocrack.apk.PackageRepository
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

/** Debug-only real-device validation of the production Agent native/Rizin tool path. */
class DebugNativeAgentValidationActivity : Activity() {
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
        require(packageFile.isFile && packageFile.length() > 0L) { "Rizin toolpack is missing" }

        val runner = ProcessRootCommandRunner()
        val detector = RootDetector(runner)
        val root = detector.inspect()
        check(root.isRootGranted) { root.diagnostic ?: "Root not granted" }
        val suPath = requireNotNull(root.suPath) { "Root granted without su path" }
        val layout = RuntimeLayout(applicationContext).initialize()
        val host = RootShellRuntimeEngine(layout, suPath)
        val chroot = ChrootRuntimeEngine(layout, host)
        val installer = ToolpackPackageInstaller(applicationContext, layout)
        val install = installer.install(Uri.fromFile(packageFile))
        check(install.manifest.id == RIZIN_TOOLPACK_ID) { "Unexpected toolpack id: ${install.manifest.id}" }
        check(install.manifest.version == RIZIN_TOOLPACK_VERSION) { "Unexpected Rizin version: ${install.manifest.version}" }
        val installed = installer.listInstalled().single { it.manifest.id == RIZIN_TOOLPACK_ID }
        val selfTest = installer.runSelfTests(installed, chroot)
        check(selfTest.passed) { "Rizin self-test failed: ${selfTest.results}" }

        val repository = PackageRepository(applicationContext, runner)
        val extraction = repository.extractPackage(root, packageName)
        val factory = AgentToolSessionFactory(applicationContext, runner, detector)
        val session = factory.create(extraction, allowDynamicTools = false)
        val toolNames = session.tools.map { it.name }.sorted()
        check(REQUIRED_NATIVE_TOOLS.all(toolNames::contains)) { "Production Agent session is missing native tools: $toolNames" }
        val events = JSONArray()

        suspend fun call(name: String, args: JSONObject = JSONObject()): JSONObject {
            val result = JSONObject(session.dispatch(name, args))
            check(result.optBoolean("ok")) { "$name returned ok=false: $result" }
            events.put(JSONObject().put("tool", name).put("ok", true).put("at", System.currentTimeMillis()))
            return result
        }

        try {
            val libraries = call("native_list_libraries")
            val array = libraries.getJSONArray("libraries")
            val targetEntry = (0 until array.length())
                .map { array.getJSONObject(it) }
                .firstOrNull { it.optString("fileName") == TARGET_LIBRARY }
                ?: error("$TARGET_LIBRARY was not found in the AutoCrack APK")
            check(targetEntry.optLong("sizeBytes") > 0L)

            val elf = call("native_elf_info", JSONObject().put("library", TARGET_LIBRARY))
            check(elf.optString("elfClass") == "ELF64") { "Unexpected ELF class: $elf" }
            check(elf.optString("machine").contains("AArch64", ignoreCase = true) || elf.optString("machine").contains("ARM64", ignoreCase = true)) {
                "Unexpected ELF machine: ${elf.optString("machine")}" }

            val jni = call("native_jni_map", JSONObject().put("library", TARGET_LIBRARY))
            val mappings = jni.getJSONArray("staticMappings")
            check(mappings.length() >= EXPECTED_MIN_JNI_EXPORTS) { "Expected >=$EXPECTED_MIN_JNI_EXPORTS static JNI mappings, got ${mappings.length()}" }
            val nativeOpen = (0 until mappings.length())
                .map { mappings.getJSONObject(it) }
                .firstOrNull {
                    it.optString("className") == EXPECTED_JNI_CLASS && it.optString("methodName") == "nativeOpen"
                }
                ?: error("JNI mapping did not recover $EXPECTED_JNI_CLASS.nativeOpen")

            val functions = call(
                "native_rizin_functions",
                JSONObject().put("library", TARGET_LIBRARY).put("max_count", 128),
            )
            val functionArray = functions.getJSONArray("functions")
            check(functionArray.length() > 0) { "Rizin returned no functions" }
            val location = "sym.${nativeOpen.getString("symbol")}"

            val disassembly = call(
                "native_rizin_disassemble",
                JSONObject()
                    .put("library", TARGET_LIBRARY)
                    .put("location", location)
                    .put("instruction_count", 32),
            )
            check(disassembly.optInt("instructionCount") > 0) { "Rizin disassembly returned no instructions" }

            val deep = call("native_rizin_report", JSONObject().put("library", TARGET_LIBRARY))
            val counts = deep.getJSONObject("summary").getJSONObject("counts")
            check(counts.optInt("functions") > 0) { "Deep report contains no functions" }
            check(counts.optInt("imports") > 0) { "Deep report contains no imports" }

            return JSONObject()
                .put("success", true)
                .put("toolpackId", install.manifest.id)
                .put("toolpackVersion", install.manifest.version)
                .put("selfTestPassed", selfTest.passed)
                .put("productionToolCount", toolNames.size)
                .put("nativeToolNames", JSONArray(toolNames.filter { it.startsWith("native_") }))
                .put("targetLibrary", TARGET_LIBRARY)
                .put("targetLibrarySize", targetEntry.optLong("sizeBytes"))
                .put("elfClass", elf.optString("elfClass"))
                .put("machine", elf.optString("machine"))
                .put("staticJniMappingCount", mappings.length())
                .put("nativeOpenMapping", nativeOpen)
                .put("rizinFunctionCountObserved", functions.optInt("functionCountObserved"))
                .put("disassemblyLocation", location)
                .put("disassemblyInstructionCount", disassembly.optInt("instructionCount"))
                .put("deepReportCounts", counts)
                .put("events", events)
        } finally {
            session.closeSafely()
        }
    }

    private companion object {
        const val RIZIN_TOOLPACK_ID = "rizin-deep-static"
        const val RIZIN_TOOLPACK_VERSION = "rizin-0.9.1_autocrack-1.0.1"
        const val TOOLPACK_INPUT_PATH = "debug-validation/rizin-deep-static-toolpack.zip"
        const val REPORT_PATH = "debug-validation/native-agent-rizin-report.json"
        const val TARGET_LIBRARY = "libautocrack_pty.so"
        const val EXPECTED_JNI_CLASS = "com.luckylca.autocrack.runtime.NativePtyBridge"
        const val EXPECTED_MIN_JNI_EXPORTS = 9
        val REQUIRED_NATIVE_TOOLS = setOf(
            "native_list_libraries",
            "native_elf_info",
            "native_jni_map",
            "native_rizin_functions",
            "native_rizin_disassemble",
            "native_rizin_report",
        )
    }
}
