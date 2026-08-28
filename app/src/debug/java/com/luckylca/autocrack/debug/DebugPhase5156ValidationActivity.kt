package com.luckylca.autocrack.debug

import android.app.Activity
import android.os.Bundle
import com.luckylca.autocrack.root.ProcessRootCommandRunner
import com.luckylca.autocrack.root.RootDetector
import com.luckylca.autocrack.runtime.DynamicHostReadBridge
import com.luckylca.autocrack.runtime.GdbRemoteRegisterValueDecoder
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

/** Debug-build-only Phase 5.15.6 device validation. Removed after validation. */
class DebugPhase5156ValidationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread {
            val report = runCatching { runBlocking { validate() } }.getOrElse { exception ->
                JSONObject()
                    .put("success", false)
                    .put("failure", exception.message ?: exception::class.java.name)
                    .put("exception", exception::class.java.name)
            }
            val file = File(filesDir, "debug-validation/phase5156-report.json")
            file.parentFile?.mkdirs()
            file.writeText(report.toString(2), Charsets.UTF_8)
            runOnUiThread { finish() }
        }.start()
    }

    private suspend fun validate(): JSONObject {
        val packageName = requireNotNull(intent.getStringExtra("target_package"))
        val pid = intent.getIntExtra("target_pid", -1)
        require(pid > 0) { "Invalid target PID" }

        val layout = RuntimeLayout(applicationContext).initialize()
        val runner = ProcessRootCommandRunner()
        val rootDetector = RootDetector(runner)
        val readBridge = DynamicHostReadBridge(layout, rootDetector, runner)
        val manager = HostDebuggerSessionManager(applicationContext, layout, rootDetector, runner)
        val control = HostDebuggerControlBridge(manager, readBridge)
        var serverStarted = false
        var clientConnected = false
        var breakpointAddress: Long? = null
        val events = JSONArray()

        fun event(name: String, detail: JSONObject = JSONObject()) {
            events.put(detail.put("event", name).put("at", System.currentTimeMillis()))
        }

        try {
            val server = manager.start(
                packageName = packageName,
                pid = pid,
                port = HostDebuggerSessionManager.DEFAULT_PORT,
                authorizationPhrase = HostDebuggerAuthorization.expected(packageName, pid),
            )
            serverStarted = true
            check(server.serverReadyForClient) { "LLDB server not ready" }

            var snapshot = control.connect(HostDebuggerControlAuthorization.expected(packageName, pid))
            clientConnected = true
            snapshot = control.refreshThreads()
            snapshot = control.readRegisters(64)
            val pcRegister = snapshot.registers.firstOrNull { it.name.equals("pc", ignoreCase = true) }
                ?: error("PC register missing")
            val pc = GdbRemoteRegisterValueDecoder.unsignedLittleEndianLong(pcRegister.rawHex)
            breakpointAddress = pc
            event("initial_pc", JSONObject().put("pc", "0x${pc.toString(16)}"))

            snapshot = control.setHardwareExecutionBreakpoint(pc)
            check(snapshot.breakpoints.singleOrNull { it.address == pc }?.autoManaged == false) {
                "Manual breakpoint was not tracked as manual"
            }
            event("manual_breakpoint_set")

            snapshot = control.autoPrepareSteppableAnchor()
            check(snapshot.autoAnchorAddress == pc) { "Auto anchor did not reuse the manual PC breakpoint" }
            event(
                "auto_anchor_reused_manual_breakpoint",
                JSONObject().put("autoAddress", "0x${pc.toString(16)}"),
            )

            control.continueTarget()
            var stopped = false
            repeat(150) {
                if (!stopped) {
                    delay(100)
                    stopped = !control.snapshot().targetRunning
                }
            }
            if (!stopped && control.snapshot().targetRunning) {
                runCatching { control.interrupt() }
                repeat(50) {
                    if (!stopped) {
                        delay(100)
                        stopped = !control.snapshot().targetRunning
                    }
                }
            }
            check(stopped) { "Target did not return to a trusted stopped state" }

            snapshot = control.snapshot()
            val tracked = snapshot.breakpoints.singleOrNull { it.address == pc }
                ?: error("Manual breakpoint disappeared unexpectedly")
            val code = snapshot.codeContext ?: error("No code context captured after stop")
            event(
                "stop_observed",
                JSONObject()
                    .put("hitCount", tracked.hitCount)
                    .put("lastHitThreadId", tracked.lastHitThreadId ?: JSONObject.NULL)
                    .put("codePc", "0x${code.pc.toString(16)}")
                    .put("framePointer", code.framePointer?.let { "0x${it.toString(16)}" } ?: JSONObject.NULL)
                    .put("stackFrames", code.stack.frames.size)
                    .put("stackPartial", code.stack.partial)
                    .put("stackTermination", code.stack.termination),
            )
            check(tracked.hitCount >= 1) { "Manual hardware breakpoint hit was not accounted" }
            check(code.stack.frames.isNotEmpty()) { "Bounded call stack contains no trusted PC frame" }

            val frames = JSONArray(code.stack.frames.map { frame ->
                JSONObject()
                    .put("index", frame.index)
                    .put("address", "0x${frame.address.toString(16)}")
                    .put("framePointer", frame.framePointer?.let { "0x${it.toString(16)}" } ?: JSONObject.NULL)
                    .put("modulePath", frame.modulePath)
                    .put("moduleOffset", "0x${frame.moduleOffset.toString(16)}")
                    .put("source", frame.source)
            })

            snapshot = control.removeHardwareExecutionBreakpoint(pc)
            breakpointAddress = null
            check(snapshot.breakpoints.none { it.address == pc }) { "Manual breakpoint cleanup failed" }
            control.prepareForDetach()
            clientConnected = false
            val detached = manager.stop() ?: error("Debugger server session vanished")
            serverStarted = false
            control.resetAfterDetach()
            check(detached.detachVerified && detached.tracerPidCurrent == 0) { "Detach verification failed" }

            return JSONObject()
                .put("success", true)
                .put("packageName", packageName)
                .put("pid", pid)
                .put("breakpointAddress", "0x${pc.toString(16)}")
                .put("hitCount", tracked.hitCount)
                .put("stackFrameCount", code.stack.frames.size)
                .put("stackPartial", code.stack.partial)
                .put("stackTermination", code.stack.termination)
                .put("frames", frames)
                .put("registerWriteCommandSent", snapshot.registerWriteCommandSent)
                .put("memoryWriteCommandSent", snapshot.memoryWriteCommandSent)
                .put("detachVerified", detached.detachVerified)
                .put("tracerPidCurrent", detached.tracerPidCurrent)
                .put("events", events)
        } finally {
            val address = breakpointAddress
            if (clientConnected && address != null && !control.snapshot().targetRunning) {
                runCatching { control.removeHardwareExecutionBreakpoint(address) }
            }
            if (clientConnected) runCatching { control.prepareForDetach() }
            if (serverStarted) runCatching { manager.stop() }
            runCatching { control.resetAfterDetach() }
        }
    }
}
