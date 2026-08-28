package com.luckylca.autocrack.debug

import android.app.Activity
import android.os.Bundle
import com.luckylca.autocrack.agent.AgentFridaToolExecutor
import com.luckylca.autocrack.root.ProcessRootCommandRunner
import com.luckylca.autocrack.root.RootDetector
import com.luckylca.autocrack.runtime.HostFridaAuthorization
import com.luckylca.autocrack.runtime.HostFridaSessionManager
import com.luckylca.autocrack.runtime.RuntimeLayout
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** Debug-only real-device validation of the Agent-facing bounded Frida dispatcher. */
class DebugAgentFridaValidationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread {
            val report = runCatching { runBlocking { validateAgentFrida() } }.getOrElse { error ->
                JSONObject()
                    .put("success", false)
                    .put("failure", error.message ?: error::class.java.name)
                    .put("exception", error::class.java.name)
            }
            val output = File(filesDir, "debug-validation/agent-frida-report.json")
            output.parentFile?.mkdirs()
            output.writeText(report.toString(2), Charsets.UTF_8)
            runOnUiThread { finish() }
        }.start()
    }

    private suspend fun validateAgentFrida(): JSONObject {
        val packageName = requireNotNull(intent.getStringExtra("target_package"))
        val pid = intent.getIntExtra("target_pid", -1)
        require(pid > 0) { "Invalid target PID" }
        val layout = RuntimeLayout(applicationContext).initialize()
        val runner = ProcessRootCommandRunner()
        val root = RootDetector(runner)
        val manager = HostFridaSessionManager(applicationContext, layout, root, runner)
        val executor = AgentFridaToolExecutor.authorized(
            packageName = packageName,
            pid = pid,
            authorizationPhrase = HostFridaAuthorization.expected(packageName, pid),
            manager = manager,
        )
        val events = JSONArray()

        suspend fun call(name: String, args: JSONObject = JSONObject()): JSONObject {
            val result = JSONObject(executor.dispatch(name, args))
            events.put(JSONObject().put("tool", name).put("ok", result.optBoolean("ok")).put("at", System.currentTimeMillis()))
            check(result.optBoolean("ok")) { "$name returned ok=false: $result" }
            if (result.has("succeeded")) {
                check(result.optBoolean("succeeded")) { "$name operation failed: $result" }
            }
            return result
        }

        try {
            val start = call("frida_start")
            check(start.optBoolean("serverReadyForClient")) { "Frida server did not become ready" }
            val ping = call("frida_ping")
            val pingClient = ping.getJSONObject("result")
            check(pingClient.optBoolean("ok")) { "Frida ping client failed: $pingClient" }
            val agent = pingClient.getJSONObject("result")
            check(agent.optString("fridaVersion") == "17.17.0") {
                "Unexpected Frida runtime version: $agent"
            }

            val modules = call("frida_modules", JSONObject().put("max_count", 256))
            val modulesPayload = modules.getJSONObject("result").getJSONArray("result")
            check(modulesPayload.length() > 0) { "No modules returned" }
            var libcName: String? = null
            for (index in 0 until modulesPayload.length()) {
                val item = modulesPayload.getJSONObject(index)
                if (item.optString("name") == "libc.so") libcName = "libc.so"
            }
            val libc = requireNotNull(libcName) { "libc.so not present in target" }

            val exports = call(
                "frida_exports",
                JSONObject().put("module", libc).put("query", "getpid").put("max_count", 64),
            )
            val exportsPayload = exports.getJSONObject("result").getJSONObject("result")
            val exportArray = exportsPayload.getJSONArray("exports")
            check(exportArray.length() > 0) { "libc getpid export not found" }
            val firstExport = exportArray.getJSONObject(0)
            val address = firstExport.getString("address")
            val moduleBase = exportsPayload.getString("base")
            val offset = "0x${(address.removePrefix("0x").toLong(16) - moduleBase.removePrefix("0x").toLong(16)).toString(16)}"

            val javaClasses = call(
                "frida_java_classes",
                JSONObject().put("query", packageName).put("max_count", 256),
            )
            val classPayload = javaClasses.getJSONObject("result").getJSONObject("result")
            val classes = classPayload.getJSONArray("classes")
            check(classes.length() > 0) { "No target Java classes returned" }
            val className = (0 until classes.length())
                .map { classes.getString(it) }
                .firstOrNull { it.startsWith(packageName) }
                ?: classes.getString(0)
            val javaMethods = call(
                "frida_java_methods",
                JSONObject().put("class_name", className).put("max_count", 128),
            )
            val methodPayload = javaMethods.getJSONObject("result").getJSONObject("result")
            check(methodPayload.optBoolean("available")) { "Java runtime unexpectedly unavailable" }

            val trace = call(
                "frida_native_trace",
                JSONObject()
                    .put("module", libc)
                    .put("offset", offset)
                    .put("duration_ms", 250)
                    .put("max_events", 16),
            )
            val traceClient = trace.getJSONObject("result")
            check(traceClient.optBoolean("ok")) { "Native trace RPC failed: $traceClient" }

            val stop = call("frida_stop")
            check(!stop.optBoolean("active")) { "Frida helper still active after stop" }
            check(stop.optInt("targetTracerPid", -1) == 0) { "Target remained traced after Frida stop" }
            return JSONObject()
                .put("success", true)
                .put("packageName", packageName)
                .put("pid", pid)
                .put("toolDefinitionCount", executor.tools.size)
                .put("fridaVersion", agent.optString("fridaVersion"))
                .put("moduleCount", modulesPayload.length())
                .put("libcExportCount", exportArray.length())
                .put("javaClassCount", classes.length())
                .put("javaClass", className)
                .put("javaMethodCount", methodPayload.getJSONArray("methods").length())
                .put("trace", traceClient)
                .put("targetTracerPid", stop.optInt("targetTracerPid", -1))
                .put("events", events)
        } finally {
            executor.closeSafely()
        }
    }
}
