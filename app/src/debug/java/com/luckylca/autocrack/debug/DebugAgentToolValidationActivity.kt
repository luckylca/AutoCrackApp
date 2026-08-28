package com.luckylca.autocrack.debug

import android.app.Activity
import android.os.Bundle
import com.luckylca.autocrack.agent.AgentDebuggerToolExecutor
import com.luckylca.autocrack.root.ProcessRootCommandRunner
import com.luckylca.autocrack.root.RootDetector
import com.luckylca.autocrack.runtime.DynamicHostReadBridge
import com.luckylca.autocrack.runtime.HostDebuggerAuthorization
import com.luckylca.autocrack.runtime.HostDebuggerControlAuthorization
import com.luckylca.autocrack.runtime.HostDebuggerControlBridge
import com.luckylca.autocrack.runtime.HostDebuggerSessionManager
import com.luckylca.autocrack.runtime.RuntimeLayout
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** Debug-only real-device validation of the Agent-facing debugger tool dispatcher. */
class DebugAgentToolValidationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread {
            val report = runCatching { runBlocking { validateAgentTools() } }.getOrElse { error ->
                JSONObject()
                    .put("success", false)
                    .put("failure", error.message ?: error::class.java.name)
                    .put("exception", error::class.java.name)
            }
            val file = File(filesDir, "debug-validation/agent-tool-dispatch-report.json")
            file.parentFile?.mkdirs()
            file.writeText(report.toString(2), Charsets.UTF_8)
            runOnUiThread { finish() }
        }.start()
    }

    private suspend fun validateAgentTools(): JSONObject {
        val targetPackage = requireNotNull(intent.getStringExtra("target_package"))
        val targetPid = intent.getIntExtra("target_pid", -1)
        require(targetPid > 0) { "Invalid target PID" }

        val layout = RuntimeLayout(applicationContext).initialize()
        val runner = ProcessRootCommandRunner()
        val root = RootDetector(runner)
        val readBridge = DynamicHostReadBridge(layout, root, runner)
        val manager = HostDebuggerSessionManager(applicationContext, layout, root, runner)
        val control = HostDebuggerControlBridge(manager, readBridge)
        val executor = AgentDebuggerToolExecutor.authorized(
            packageName = targetPackage,
            pid = targetPid,
            attachAuthorization = HostDebuggerAuthorization.expected(targetPackage, targetPid),
            controlAuthorization = HostDebuggerControlAuthorization.expected(targetPackage, targetPid),
            readBridge = readBridge,
            manager = manager,
            control = control,
        )
        val events = JSONArray()

        suspend fun call(name: String, args: JSONObject = JSONObject()): JSONObject {
            val result = JSONObject(executor.dispatch(name, args))
            events.put(
                JSONObject()
                    .put("tool", name)
                    .put("ok", result.optBoolean("ok"))
                    .put("at", System.currentTimeMillis()),
            )
            check(result.optBoolean("ok")) { "$name returned ok=false: $result" }
            return result
        }

        try {
            call("inspect_target")
            call("debugger_attach")
            val threads = call("debugger_threads", JSONObject().put("max_count", 64))
            val registers = call("debugger_read_registers", JSONObject().put("max_count", 64))
            val controlJson = registers.getJSONObject("control")
            val registerArray = controlJson.getJSONArray("registers")
            var pc: String? = null
            for (index in 0 until registerArray.length()) {
                val register = registerArray.getJSONObject(index)
                if (register.optString("name").equals("pc", ignoreCase = true)) {
                    pc = littleEndianRegisterHexToAddress(register.getString("rawHex"))
                    break
                }
            }
            val pcAddress = requireNotNull(pc) { "Agent register tool did not return PC" }
            val memory = call(
                "debugger_read_memory",
                JSONObject().put("address", pcAddress).put("length", 16),
            )
            call("debugger_auto_anchor")
            call("debugger_continue")

            var stopped = false
            repeat(120) {
                if (!stopped) {
                    delay(100)
                    stopped = !control.snapshot().targetRunning
                }
            }
            if (!stopped && control.snapshot().targetRunning) {
                call("debugger_interrupt")
                stopped = true
            }
            check(stopped) { "Agent tool target did not return to stopped state" }

            val step = call("debugger_step")
            val status = call("debugger_status")
            val detach = call("debugger_detach")
            val finalControl = status.getJSONObject("control")
            val memoryControl = memory.getJSONObject("control")
            val detachTracer = detach.optInt("tracerPid", -1)

            check(!finalControl.optBoolean("registerWriteCommandSent")) { "Agent path attempted register write" }
            check(!finalControl.optBoolean("memoryWriteCommandSent")) { "Agent path attempted memory write" }
            check(detach.optBoolean("detached")) { "Agent detach was not verified" }
            check(detachTracer == 0) { "TracerPid after Agent detach is $detachTracer" }

            return JSONObject()
                .put("success", true)
                .put("packageName", targetPackage)
                .put("pid", targetPid)
                .put("toolDefinitionCount", executor.tools.size)
                .put("threadCount", threads.getJSONObject("control").getJSONArray("threads").length())
                .put("registerCount", registerArray.length())
                .put("pc", pcAddress)
                .put("memoryHex", memoryControl.optString("memoryHex"))
                .put("stepLastStop", step.getJSONObject("control").optString("lastStopReply"))
                .put("registerWriteCommandSent", finalControl.optBoolean("registerWriteCommandSent"))
                .put("memoryWriteCommandSent", finalControl.optBoolean("memoryWriteCommandSent"))
                .put("detachVerified", detach.optBoolean("detached"))
                .put("tracerPidCurrent", detachTracer)
                .put("events", events)
        } finally {
            executor.closeSafely()
        }
    }

    private fun littleEndianRegisterHexToAddress(rawHex: String): String {
        val bytes = rawHex.chunked(2).map { it.toInt(16) }
        require(bytes.isNotEmpty() && bytes.size <= 8) { "Unexpected PC register width" }
        var value = 0L
        bytes.forEachIndexed { index, byte -> value = value or ((byte.toLong() and 0xffL) shl (8 * index)) }
        require(value > 0L) { "PC is zero" }
        return "0x${value.toString(16)}"
    }
}
