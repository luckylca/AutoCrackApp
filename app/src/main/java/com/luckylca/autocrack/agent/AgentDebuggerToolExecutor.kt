package com.luckylca.autocrack.agent

import com.luckylca.autocrack.runtime.DynamicHostReadBridge
import com.luckylca.autocrack.runtime.HostDebuggerAuthorization
import com.luckylca.autocrack.runtime.HostDebuggerControlAuthorization
import com.luckylca.autocrack.runtime.HostDebuggerControlBridge
import com.luckylca.autocrack.runtime.HostDebuggerControlSnapshot
import com.luckylca.autocrack.runtime.HostDebuggerSessionManager
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Agent-facing debugger dispatcher bound to one user-authorized package/PID.
 * The model never supplies packageName, pid, authorization strings, raw packets, register writes,
 * memory writes, software breakpoints, or arbitrary signals.
 */
class AgentDebuggerToolExecutor private constructor(
    private val packageName: String,
    private val pid: Int,
    private val readBridge: DynamicHostReadBridge,
    private val manager: HostDebuggerSessionManager,
    private val control: HostDebuggerControlBridge,
) : AgentToolExecutor {
    override val tools: List<AgentToolDefinition> = buildDefinitions()
    private var ownsSession = false

    override suspend fun dispatch(toolName: String, arguments: JSONObject): String {
        val result = when (toolName) {
            TOOL_INSPECT -> inspectTarget()
            TOOL_ATTACH -> attach()
            TOOL_STATUS -> status()
            TOOL_THREADS -> controlResult(control.refreshThreads(arguments.optInt("max_count", 64).coerceIn(1, 256)))
            TOOL_SELECT_THREAD -> controlResult(control.selectThread(arguments.requireString("thread_id")))
            TOOL_REGISTERS -> controlResult(control.readRegisters(arguments.optInt("max_count", 64).coerceIn(1, 128)))
            TOOL_MEMORY -> {
                val address = parseAddress(arguments.requireString("address"))
                val length = arguments.optInt("length", 64).coerceIn(1, 512)
                controlResult(control.readMemory(address, length))
            }
            TOOL_BREAKPOINT_SET -> controlResult(
                control.setHardwareExecutionBreakpoint(parseAddress(arguments.requireString("address"))),
            )
            TOOL_BREAKPOINT_REMOVE -> controlResult(
                control.removeHardwareExecutionBreakpoint(parseAddress(arguments.requireString("address"))),
            )
            TOOL_AUTO_ANCHOR -> controlResult(control.autoPrepareSteppableAnchor())
            TOOL_STEP -> controlResult(control.step())
            TOOL_CONTINUE -> controlResult(control.continueTarget())
            TOOL_INTERRUPT -> controlResult(control.interrupt())
            TOOL_DETACH -> detach()
            else -> error("Unknown or unauthorized Agent debugger tool: $toolName")
        }
        return result.put("ok", true).put("tool", toolName).toString()
    }

    override suspend fun closeSafely() {
        if (!ownsSession) return
        runCatching {
            val snapshot = control.snapshot()
            if (snapshot.connected) {
                if (snapshot.targetRunning) control.interrupt()
                control.prepareForDetach()
            }
        }
        runCatching { manager.stop() }
        control.resetAfterDetach()
        ownsSession = false
    }

    private suspend fun inspectTarget(): JSONObject {
        val report = readBridge.inspectProcess(pid)
        return JSONObject()
            .put("packageName", packageName)
            .put("pid", pid)
            .put("succeeded", report.succeeded)
            .put("identity", commandSummary(report.identity.stdout, report.identity.stderr, report.identity.exitCode))
            .put("attachPreflight", commandSummary(report.attachPreflight.stdout, report.attachPreflight.stderr, report.attachPreflight.exitCode))
            .put("threadText", report.threads.stdout.take(MAX_TEXT_CHARS))
            .put("loadedModules", JSONArray(report.loadedModules.take(MAX_MODULES).map { module ->
                JSONObject()
                    .put("path", module.path)
                    .put("start", hex(module.firstAddress))
                    .put("end", hex(module.lastAddressExclusive))
                    .put("segments", module.segmentCount)
                    .put("executable", module.executable)
            }))
    }

    private suspend fun attach(): JSONObject {
        val existing = manager.refresh()
        if (existing == null) {
            manager.start(
                packageName = packageName,
                pid = pid,
                port = HostDebuggerSessionManager.DEFAULT_PORT,
                authorizationPhrase = HostDebuggerAuthorization.expected(packageName, pid),
            )
            ownsSession = true
        } else {
            require(ownsSession && existing.packageName == packageName && existing.pid == pid) {
                "A debugger session already exists and is not owned by this Agent run"
            }
        }
        if (!control.snapshot().connected) {
            control.connect(HostDebuggerControlAuthorization.expected(packageName, pid))
        }
        return status()
    }

    private suspend fun status(): JSONObject {
        val server = manager.refresh()
        return JSONObject()
            .put("packageName", packageName)
            .put("pid", pid)
            .put(
                "server",
                server?.let { snapshot ->
                    JSONObject()
                        .put("sessionId", snapshot.sessionId)
                        .put("running", snapshot.running)
                        .put("helperPid", snapshot.helperPid ?: JSONObject.NULL)
                        .put("helperVerified", snapshot.helperVerified)
                        .put("serverReadyForClient", snapshot.serverReadyForClient)
                        .put("tracerPid", snapshot.tracerPidCurrent ?: JSONObject.NULL)
                        .put("detachVerified", snapshot.detachVerified)
                        .put("failure", snapshot.failure ?: JSONObject.NULL)
                } ?: JSONObject.NULL,
            )
            .put("control", controlJson(control.snapshot()))
    }

    private suspend fun detach(): JSONObject {
        val before = control.snapshot()
        if (before.connected) {
            if (before.targetRunning) control.interrupt()
            control.prepareForDetach()
        }
        val detached = if (ownsSession) manager.stop() else null
        control.resetAfterDetach()
        ownsSession = false
        return JSONObject()
            .put("detached", detached?.detachVerified ?: true)
            .put("tracerPid", detached?.tracerPidCurrent ?: 0)
            .put("failure", detached?.failure ?: JSONObject.NULL)
    }

    private fun controlResult(snapshot: HostDebuggerControlSnapshot): JSONObject =
        JSONObject().put("control", controlJson(snapshot))

    private fun controlJson(snapshot: HostDebuggerControlSnapshot): JSONObject = JSONObject()
        .put("connected", snapshot.connected)
        .put("targetRunning", snapshot.targetRunning)
        .put("selectedThreadId", snapshot.selectedThreadId ?: JSONObject.NULL)
        .put("lastStopReply", snapshot.lastStopReply ?: JSONObject.NULL)
        .put("failure", snapshot.failure ?: JSONObject.NULL)
        .put("threads", JSONArray(snapshot.threads.take(MAX_THREADS).map { thread ->
            JSONObject()
                .put("id", thread.id)
                .put("name", thread.name ?: JSONObject.NULL)
                .put("main", thread.isMain)
        }))
        .put("registers", JSONArray(snapshot.registers.take(MAX_REGISTERS).map { register ->
            JSONObject()
                .put("index", register.index)
                .put("name", register.name)
                .put("bitSize", register.bitSize ?: JSONObject.NULL)
                .put("rawHex", register.rawHex)
        }))
        .put("memoryAddress", snapshot.lastMemoryAddress?.let(::hex) ?: JSONObject.NULL)
        .put("memoryHex", snapshot.lastMemoryHex?.take(MAX_MEMORY_HEX_CHARS) ?: JSONObject.NULL)
        .put("breakpoints", JSONArray(snapshot.breakpoints.map { breakpoint ->
            JSONObject()
                .put("address", hex(breakpoint.address))
                .put("hitCount", breakpoint.hitCount)
                .put("lastHitThreadId", breakpoint.lastHitThreadId ?: JSONObject.NULL)
                .put("autoManaged", breakpoint.autoManaged)
        }))
        .put(
            "codeContext",
            snapshot.codeContext?.let { code ->
                JSONObject()
                    .put("threadId", code.threadId)
                    .put("threadName", code.threadName ?: JSONObject.NULL)
                    .put("pc", hex(code.pc))
                    .put("lr", code.lr?.let(::hex) ?: JSONObject.NULL)
                    .put("sp", code.sp?.let(::hex) ?: JSONObject.NULL)
                    .put("framePointer", code.framePointer?.let(::hex) ?: JSONObject.NULL)
                    .put("module", code.modulePath)
                    .put("moduleOffset", hex(code.moduleOffset))
                    .put("stackPartial", code.stack.partial)
                    .put("stackTermination", code.stack.termination)
                    .put("stack", JSONArray(code.stack.frames.map { frame ->
                        JSONObject()
                            .put("index", frame.index)
                            .put("address", hex(frame.address))
                            .put("module", frame.modulePath)
                            .put("moduleOffset", hex(frame.moduleOffset))
                            .put("source", frame.source)
                    }))
                    .put("instructions", JSONArray(code.instructions.take(MAX_INSTRUCTIONS).map { instruction ->
                        JSONObject()
                            .put("address", hex(instruction.address))
                            .put("rawHex", instruction.rawHex)
                            .put("text", instruction.text)
                            .put("current", instruction.current)
                    }))
            } ?: JSONObject.NULL,
        )
        .put("timeline", JSONArray(snapshot.timeline.takeLast(MAX_TIMELINE).map { entry ->
            JSONObject()
                .put("sequence", entry.sequence)
                .put("kind", entry.kind)
                .put("threadId", entry.threadId ?: JSONObject.NULL)
                .put("pc", entry.pc?.let(::hex) ?: JSONObject.NULL)
                .put("module", entry.modulePath ?: JSONObject.NULL)
                .put("moduleOffset", entry.moduleOffset?.let(::hex) ?: JSONObject.NULL)
                .put("summary", entry.summary)
        }))
        .put("registerWriteCommandSent", snapshot.registerWriteCommandSent)
        .put("memoryWriteCommandSent", snapshot.memoryWriteCommandSent)

    private fun buildDefinitions(): List<AgentToolDefinition> {
        fun integerProperty(description: String, minimum: Int, maximum: Int): JSONObject = JSONObject()
            .put("type", "integer")
            .put("description", description)
            .put("minimum", minimum)
            .put("maximum", maximum)
        fun stringProperty(description: String): JSONObject = JSONObject()
            .put("type", "string")
            .put("description", description)
        val empty = AgentJsonSchema.emptyObject()
        return listOf(
            AgentToolDefinition(TOOL_INSPECT, "Inspect the already-authorized target process, maps, threads and attach preflight without attaching.", empty),
            AgentToolDefinition(TOOL_ATTACH, "Start the trusted loopback LLDB server and perform typed vAttach to the already-authorized target.", empty),
            AgentToolDefinition(TOOL_STATUS, "Read current debugger server/client status without changing target state.", empty),
            AgentToolDefinition(
                TOOL_THREADS,
                "Refresh the bounded target thread list.",
                AgentJsonSchema.objectSchema(JSONObject().put("max_count", integerProperty("Maximum threads to return", 1, 256))),
            ),
            AgentToolDefinition(
                TOOL_SELECT_THREAD,
                "Select one already-observed target thread for register reads and stepping.",
                AgentJsonSchema.objectSchema(JSONObject().put("thread_id", stringProperty("Thread id returned by debugger_threads")), listOf("thread_id")),
            ),
            AgentToolDefinition(
                TOOL_REGISTERS,
                "Read target registers from the selected stopped thread. Register writes are unavailable.",
                AgentJsonSchema.objectSchema(JSONObject().put("max_count", integerProperty("Maximum registers to read", 1, 128))),
            ),
            AgentToolDefinition(
                TOOL_MEMORY,
                "Read at most 512 bytes from a validated target address. Memory writes are unavailable.",
                AgentJsonSchema.objectSchema(
                    JSONObject()
                        .put("address", stringProperty("Positive target address, preferably 0x-prefixed hex"))
                        .put("length", integerProperty("Bytes to read", 1, 512)),
                    listOf("address"),
                ),
            ),
            AgentToolDefinition(
                TOOL_BREAKPOINT_SET,
                "Set one typed AArch64 hardware execution breakpoint (Z1) at a positive 4-byte-aligned address.",
                addressSchema(),
            ),
            AgentToolDefinition(
                TOOL_BREAKPOINT_REMOVE,
                "Remove a hardware execution breakpoint already tracked by this AutoCrack session.",
                addressSchema(),
            ),
            AgentToolDefinition(TOOL_AUTO_ANCHOR, "Prepare the bounded main-thread/current-PC one-shot hardware anchor for reaching a steppable user-space stop.", empty),
            AgentToolDefinition(TOOL_STEP, "Single-step only the currently selected stopped thread.", empty),
            AgentToolDefinition(TOOL_CONTINUE, "Continue the target using the typed debugger control path.", empty),
            AgentToolDefinition(TOOL_INTERRUPT, "Interrupt a currently running target through the debugger protocol and capture a trusted stop.", empty),
            AgentToolDefinition(TOOL_DETACH, "Safely detach the debugger, stop only the verified helper and require target TracerPid to return to 0.", empty),
        )
    }

    private fun addressSchema(): JSONObject = AgentJsonSchema.objectSchema(
        JSONObject().put(
            "address",
            JSONObject()
                .put("type", "string")
                .put("description", "Positive 4-byte-aligned target address, preferably 0x-prefixed hex"),
        ),
        listOf("address"),
    )

    private fun JSONObject.requireString(name: String): String = getString(name).trim().also {
        require(it.isNotBlank()) { "$name must not be blank" }
    }

    companion object {
        fun authorized(
            packageName: String,
            pid: Int,
            attachAuthorization: String,
            controlAuthorization: String,
            readBridge: DynamicHostReadBridge,
            manager: HostDebuggerSessionManager,
            control: HostDebuggerControlBridge,
        ): AgentDebuggerToolExecutor {
            HostDebuggerAuthorization.requireAuthorized(packageName, pid, attachAuthorization)
            HostDebuggerControlAuthorization.requireAuthorized(packageName, pid, controlAuthorization)
            return AgentDebuggerToolExecutor(packageName, pid, readBridge, manager, control)
        }

        private const val TOOL_INSPECT = "inspect_target"
        private const val TOOL_ATTACH = "debugger_attach"
        private const val TOOL_STATUS = "debugger_status"
        private const val TOOL_THREADS = "debugger_threads"
        private const val TOOL_SELECT_THREAD = "debugger_select_thread"
        private const val TOOL_REGISTERS = "debugger_read_registers"
        private const val TOOL_MEMORY = "debugger_read_memory"
        private const val TOOL_BREAKPOINT_SET = "debugger_set_hardware_breakpoint"
        private const val TOOL_BREAKPOINT_REMOVE = "debugger_remove_hardware_breakpoint"
        private const val TOOL_AUTO_ANCHOR = "debugger_auto_anchor"
        private const val TOOL_STEP = "debugger_step"
        private const val TOOL_CONTINUE = "debugger_continue"
        private const val TOOL_INTERRUPT = "debugger_interrupt"
        private const val TOOL_DETACH = "debugger_detach"
        private const val MAX_MODULES = 64
        private const val MAX_THREADS = 64
        private const val MAX_REGISTERS = 128
        private const val MAX_INSTRUCTIONS = 16
        private const val MAX_TIMELINE = 32
        private const val MAX_TEXT_CHARS = 12_000
        private const val MAX_MEMORY_HEX_CHARS = 1_024

        private fun parseAddress(text: String): Long {
            val normalized = text.trim().lowercase(Locale.ROOT)
            val value = if (normalized.startsWith("0x")) {
                normalized.removePrefix("0x").toLongOrNull(16)
            } else {
                normalized.toLongOrNull()
            }
            return requireNotNull(value) { "Invalid address: $text" }.also {
                require(it > 0L) { "Address must be positive" }
            }
        }

        private fun hex(value: Long): String = "0x${value.toString(16)}"

        private fun commandSummary(stdout: String, stderr: String, exitCode: Int?): JSONObject = JSONObject()
            .put("exitCode", exitCode ?: JSONObject.NULL)
            .put("stdout", stdout.take(MAX_TEXT_CHARS))
            .put("stderr", stderr.take(MAX_TEXT_CHARS))
    }
}
