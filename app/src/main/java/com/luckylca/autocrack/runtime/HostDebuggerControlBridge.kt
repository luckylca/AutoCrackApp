package com.luckylca.autocrack.runtime

import com.luckylca.autocrack.apk.PackageOutputParser
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

object HostDebuggerControlAuthorization {
    fun expected(packageName: String, pid: Int): String {
        PackageOutputParser.requireValidPackageName(packageName)
        require(pid > 0) { "PID must be positive" }
        return "CONTROL $packageName $pid"
    }

    fun requireAuthorized(packageName: String, pid: Int, supplied: String) {
        val expected = expected(packageName, pid)
        require(supplied.trim() == expected) {
            "continue/step 会恢复目标执行；请输入精确控制授权短语：$expected"
        }
    }
}

/** Only a verified targetless server with a real IPv4 listener may enter the CONTROL stage. */
object HostDebuggerControlGate {
    fun canAttemptConnection(
        running: Boolean,
        helperVerified: Boolean,
        serverReadyForClient: Boolean,
        tracerPidCurrent: Int?,
        failure: String?,
    ): Boolean =
        running && helperVerified && serverReadyForClient && failure == null && tracerPidCurrent == 0

    fun canAttemptConnection(server: HostDebuggerSessionSnapshot): Boolean = canAttemptConnection(
        running = server.running,
        helperVerified = server.helperVerified,
        serverReadyForClient = server.serverReadyForClient,
        tracerPidCurrent = server.tracerPidCurrent,
        failure = server.failure,
    )
}

data class HostDebuggerRegisterSnapshot(
    val index: Int,
    val name: String,
    val bitSize: Int?,
    val rawHex: String,
)

data class HostDebuggerThreadSnapshot(
    val id: String,
    val name: String?,
    val isMain: Boolean,
)

data class HostDebuggerBreakpointSnapshot(
    val address: Long,
    val kindBytes: Int,
    val hitCount: Int = 0,
    val lastHitThreadId: String? = null,
    val lastHitAtEpochMillis: Long? = null,
    val autoManaged: Boolean = false,
)

object HostDebuggerBreakpointHitAccounting {
    fun applyTrustedStop(
        breakpoints: List<HostDebuggerBreakpointSnapshot>,
        stopReply: String,
        context: HostDebuggerCodeContextSnapshot,
        timestampEpochMillis: Long,
    ): List<HostDebuggerBreakpointSnapshot> = breakpoints.map { breakpoint ->
        if (
            !breakpoint.autoManaged &&
            GdbRemoteHardwareBreakpointHitPolicy.isTrustedHit(
                stopReply = stopReply,
                stopPc = context.pc,
                breakpointAddress = breakpoint.address,
            )
        ) {
            breakpoint.copy(
                hitCount = breakpoint.hitCount + 1,
                lastHitThreadId = context.threadId,
                lastHitAtEpochMillis = timestampEpochMillis,
            )
        } else {
            breakpoint
        }
    }
}

data class HostDebuggerTimelineEntry(
    val sequence: Long,
    val timestampEpochMillis: Long,
    val kind: String,
    val threadId: String?,
    val pc: Long?,
    val modulePath: String?,
    val moduleOffset: Long?,
    val instructionRawHex: String?,
    val instructionText: String?,
    val summary: String,
)

data class HostDebuggerControlSnapshot(
    val sessionId: String?,
    val packageName: String?,
    val pid: Int?,
    val port: Int?,
    val connected: Boolean,
    val controlAuthorizationVerified: Boolean,
    val targetRunning: Boolean,
    val lastStopReply: String?,
    val capabilities: List<String>,
    val threads: List<HostDebuggerThreadSnapshot>,
    val selectedThreadId: String?,
    val lastStepThreadId: String?,
    val stepCompleted: Boolean,
    val threadListCommandSent: Boolean,
    val breakpoints: List<HostDebuggerBreakpointSnapshot>,
    val breakpointSetCommandSent: Boolean,
    val breakpointRemoveCommandSent: Boolean,
    val autoAnchorPrepared: Boolean,
    val autoAnchorThreadId: String?,
    val autoAnchorAddress: Long?,
    val autoAnchorStopObserved: Boolean,
    val codeContext: HostDebuggerCodeContextSnapshot?,
    val codeContextFailure: String?,
    val autoAnchorAutoResumed: Boolean,
    val autoSignalPassthroughCount: Int,
    val lastAutoPassedSignal: Int?,
    val timeline: List<HostDebuggerTimelineEntry>,
    val registers: List<HostDebuggerRegisterSnapshot>,
    val lastMemoryAddress: Long?,
    val lastMemoryHex: String?,
    val continueCommandSent: Boolean,
    val stepCommandSent: Boolean,
    val stepAutoInterruptRecovered: Boolean,
    val interruptCommandSent: Boolean,
    val registerReadCommandSent: Boolean,
    val memoryReadCommandSent: Boolean,
    val registerWriteCommandSent: Boolean,
    val memoryWriteCommandSent: Boolean,
    val breakpointCommandSent: Boolean,
    val failure: String?,
)

/**
 * Confirmation-gated client bridge for the trusted loopback lldb-server. The actual target attach
 * is a fixed typed vAttach operation and there is no arbitrary packet execution API.
 */
class HostDebuggerControlBridge(
    private val manager: HostDebuggerSessionManager,
    private val readBridge: DynamicHostReadBridge,
) {
    val auditFile: File = File(manager.auditFile.parentFile, "dynamic-debugger-control.jsonl")

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var client: HostDebuggerRemoteClient? = null
    private var controlJob: Job? = null
    private var mutable = MutableControlState()

    suspend fun connect(authorizationPhrase: String): HostDebuggerControlSnapshot =
        withContext(Dispatchers.IO) {
            val server = requireNotNull(manager.refresh()) { "当前没有 LLDB server session" }
            require(HostDebuggerControlGate.canAttemptConnection(server)) {
                when {
                    !server.running -> "LLDB server 未运行"
                    server.failure != null -> "LLDB server 状态异常：${server.failure}"
                    !server.helperVerified -> "LLDB helper 尚未通过身份复核"
                    !server.serverReadyForClient -> "LLDB server 尚未在 127.0.0.1:${server.port} 进入 LISTEN"
                    server.tracerPidCurrent != 0 -> "目标已被 tracer ${server.tracerPidCurrent} 附加；拒绝建立新控制客户端"
                    else -> "LLDB server 当前状态不允许建立控制客户端"
                }
            }
            HostDebuggerControlAuthorization.requireAuthorized(server.packageName, server.pid, authorizationPhrase)

            synchronized(lock) {
                require(client?.connected != true) { "LLDB client 已连接" }
                mutable = MutableControlState(
                    sessionId = server.sessionId,
                    packageName = server.packageName,
                    pid = server.pid,
                    port = server.port,
                    controlAuthorizationVerified = true,
                )
            }

            val created = HostDebuggerRemoteClient(server.port)
            var attachPacketSent = false
            try {
                val handshake = created.connect()
                synchronized(lock) {
                    mutable.capabilities = handshake.capabilities.sorted()
                    mutable.targetRunning = false
                }
                appendAudit("client_transport_connected_targetless")

                val revalidated = manager.prepareClientAttach(server.sessionId)
                require(revalidated.pid == server.pid && revalidated.packageName == server.packageName) {
                    "Debugger target identity changed before vAttach"
                }

                attachPacketSent = true
                val attachReply = created.attach(server.pid)
                synchronized(lock) { mutable.lastStopReply = attachReply }
                appendAudit("typed_vattach_stop_reply")

                val confirmedServer = awaitConfirmedAttach(server.sessionId)
                require(
                    confirmedServer != null && confirmedServer.running && confirmedServer.failure == null &&
                        confirmedServer.attachedObserved && confirmedServer.tracerPidCurrent != null &&
                        confirmedServer.tracerPidCurrent > 0,
                ) {
                    val latest = confirmedServer
                    "vAttach 已发送，但未确认可信 traced 状态：running=${latest?.running ?: false}, " +
                        "tracer=${latest?.tracerPidCurrent ?: "未知"}, helper=${latest?.helperPid ?: "未知"}, " +
                        "failure=${latest?.failure ?: "无"}"
                }

                val threadInfos = runCatching {
                    created.queryThreads(DEFAULT_UI_THREAD_LIMIT)
                }.getOrElse { emptyList() }
                val threadSnapshots = threadInfos.map { info ->
                    HostDebuggerThreadSnapshot(
                        id = info.id,
                        name = info.name,
                        isMain = GdbRemoteThreadIdValidator.matchesTid(info.id, server.pid),
                    )
                }
                val stopThreadId = GdbRemoteStopReplyParser.threadId(attachReply)
                val directMainThreadId = server.pid.toString(16)
                val selectedThreadId =
                    runCatching {
                        created.selectGeneralThread(directMainThreadId)
                        directMainThreadId
                    }.getOrNull()
                        ?: threadSnapshots.firstOrNull { it.isMain }?.id
                        ?: stopThreadId?.takeIf { stopped ->
                            threadSnapshots.isEmpty() || threadSnapshots.any { it.id == stopped }
                        }
                        ?: threadSnapshots.firstOrNull()?.id
                if (selectedThreadId != null && selectedThreadId != directMainThreadId) {
                    runCatching { created.selectGeneralThread(selectedThreadId) }
                }

                synchronized(lock) {
                    client = created
                    mutable.connected = true
                    mutable.threads = threadSnapshots
                    mutable.selectedThreadId = selectedThreadId
                    mutable.threadListCommandSent = threadInfos.isNotEmpty()
                    mutable.failure = null
                }
                appendAudit("client_connected_attach_confirmed")
                if (threadInfos.isNotEmpty()) appendAudit("threads_observed_after_attach")
                snapshot()
            } catch (exception: Exception) {
                created.close()
                if (attachPacketSent) {
                    runCatching { manager.stop() }
                    appendAudit("client_attach_failed_safe_helper_teardown")
                }
                synchronized(lock) {
                    client = null
                    mutable.connected = false
                    mutable.targetRunning = false
                    mutable.failure = exception.message ?: exception::class.java.simpleName
                }
                appendAudit("client_connect_failed")
                throw exception
            }
        }

    fun snapshot(): HostDebuggerControlSnapshot = synchronized(lock) { snapshotLocked() }

    suspend fun setHardwareExecutionBreakpoint(address: Long): HostDebuggerControlSnapshot =
        withContext(Dispatchers.IO) {
            requireStoppedClient()
            val normalized = GdbRemoteHardwareBreakpointValidator.normalizeAddress(address)
            synchronized(lock) {
                require(mutable.breakpoints.none { it.address == normalized }) {
                    "该硬件执行断点已由当前 AutoCrack 会话跟踪"
                }
                require(mutable.breakpoints.size < MAX_UI_HARDWARE_BREAKPOINTS) {
                    "AutoCrack 单会话最多跟踪 $MAX_UI_HARDWARE_BREAKPOINTS 个硬件执行断点"
                }
            }
            val inserted = requireNotNull(client).setHardwareExecutionBreakpoint(normalized)
            synchronized(lock) {
                mutable.breakpoints = mutable.breakpoints + HostDebuggerBreakpointSnapshot(
                    address = inserted.address,
                    kindBytes = inserted.kindBytes,
                )
                mutable.breakpointCommandSent = true
                mutable.breakpointSetCommandSent = true
                mutable.failure = null
            }
            appendAudit("hardware_execution_breakpoint_set")
            snapshot()
        }

    suspend fun removeHardwareExecutionBreakpoint(address: Long): HostDebuggerControlSnapshot =
        withContext(Dispatchers.IO) {
            requireStoppedClient()
            val normalized = GdbRemoteHardwareBreakpointValidator.normalizeAddress(address)
            synchronized(lock) {
                require(mutable.breakpoints.any { it.address == normalized }) {
                    "只能移除当前 AutoCrack 会话已经设置并跟踪的硬件执行断点"
                }
            }
            requireNotNull(client).removeHardwareExecutionBreakpoint(normalized)
            synchronized(lock) {
                mutable.breakpoints = mutable.breakpoints.filterNot { it.address == normalized }
                mutable.breakpointCommandSent = true
                mutable.breakpointRemoveCommandSent = true
                mutable.failure = null
            }
            appendAudit("hardware_execution_breakpoint_removed")
            snapshot()
        }

    /**
     * Automatically prepare a one-shot execution anchor that gets the user out of an idle/blocking
     * syscall without asking them to know a module address or manually pick a thread.
     *
     * Strategy: prefer the process leader (main TID), read only its architecture-described PC,
     * verify that the instruction bytes are readable, and place the existing typed Z1 hardware
     * execution breakpoint on that exact PC. The UI may then Continue. When execution returns to
     * this user-space PC, the next stop is automatically selected as the register/step context and
     * the one-shot breakpoint is removed.
     */
    suspend fun autoPrepareSteppableAnchor(): HostDebuggerControlSnapshot =
        withContext(Dispatchers.IO) {
            requireStoppedClient()
            val active = requireNotNull(client)
            val targetPid = synchronized(lock) { requireNotNull(mutable.pid) }
            val directMainThreadId = targetPid.toString(16)

            val selectedThreadId = runCatching {
                active.selectGeneralThread(directMainThreadId)
                directMainThreadId
            }.getOrElse {
                val infos = active.queryThreads(MAX_UI_THREAD_LIMIT)
                val main = infos.firstOrNull { info ->
                    GdbRemoteThreadIdValidator.matchesTid(info.id, targetPid)
                } ?: infos.firstOrNull()
                val fallback = main?.id ?: synchronized(lock) {
                    requireNotNull(mutable.selectedThreadId) {
                        "LLDB 没有返回可用于自动选择的线程"
                    }
                }
                active.selectGeneralThread(fallback)
                fallback
            }

            val metadata = active.queryRegisters(AUTO_REGISTER_METADATA_LIMIT)
            val pcRegister =
                metadata.firstOrNull { it.genericName.equals("pc", ignoreCase = true) }
                    ?: metadata.firstOrNull { it.name.equals("pc", ignoreCase = true) }
                    ?: metadata.firstOrNull { it.index == AARCH64_PC_REGISTER_FALLBACK_INDEX }
                    ?: error("LLDB qRegisterInfo 没有提供 PC 寄存器")
            val pcValue = active.readRegister(pcRegister.index)
            val address = GdbRemoteHardwareBreakpointValidator.normalizeAddress(
                GdbRemoteRegisterValueDecoder.unsignedLittleEndianLong(pcValue.rawHex),
            )

            // Read-only preflight: reject a bogus/unmapped PC before consuming a hardware slot.
            active.readMemory(address, GdbRemoteHardwareBreakpointValidator.AARCH64_INSTRUCTION_BYTES)

            val previousAuto = synchronized(lock) {
                Pair(mutable.autoAnchorAddress, mutable.autoAnchorOwnsBreakpoint)
            }
            if (previousAuto.first != null && previousAuto.second) {
                val oldAddress = previousAuto.first!!
                if (synchronized(lock) { mutable.breakpoints.any { it.address == oldAddress } }) {
                    runCatching { active.removeHardwareExecutionBreakpoint(oldAddress) }
                    synchronized(lock) {
                        mutable.breakpoints = mutable.breakpoints.filterNot { it.address == oldAddress }
                        mutable.breakpointRemoveCommandSent = true
                    }
                }
            }

            val existing = synchronized(lock) { mutable.breakpoints.any { it.address == address } }
            if (!existing) {
                synchronized(lock) {
                    require(mutable.breakpoints.size < MAX_UI_HARDWARE_BREAKPOINTS) {
                        "AutoCrack 单会话最多跟踪 $MAX_UI_HARDWARE_BREAKPOINTS 个硬件执行断点"
                    }
                }
                val inserted = active.setHardwareExecutionBreakpoint(address)
                synchronized(lock) {
                    mutable.breakpoints = mutable.breakpoints + HostDebuggerBreakpointSnapshot(
                        address = inserted.address,
                        kindBytes = inserted.kindBytes,
                        autoManaged = true,
                    )
                    mutable.breakpointCommandSent = true
                    mutable.breakpointSetCommandSent = true
                }
            }

            synchronized(lock) {
                mutable.selectedThreadId = selectedThreadId
                mutable.registers = emptyList()
                mutable.autoAnchorPrepared = true
                mutable.autoAnchorThreadId = selectedThreadId
                mutable.autoAnchorAddress = address
                mutable.autoAnchorOwnsBreakpoint = !existing
                mutable.autoAnchorStopObserved = false
                mutable.failure = null
            }
            appendAudit("auto_steppable_anchor_prepared")
            recordTimeline(
                kind = "auto_anchor_prepared",
                summary = "main-thread/current-PC one-shot anchor prepared at 0x${address.toString(16)}",
                threadId = selectedThreadId,
            )
            snapshot()
        }

    suspend fun refreshThreads(maxCount: Int = DEFAULT_UI_THREAD_LIMIT): HostDebuggerControlSnapshot =
        withContext(Dispatchers.IO) {
            requireStoppedClient()
            require(maxCount in 1..MAX_UI_THREAD_LIMIT) {
                "线程枚举数量必须为 1..$MAX_UI_THREAD_LIMIT"
            }
            val active = requireNotNull(client)
            val targetPid = synchronized(lock) { requireNotNull(mutable.pid) }
            val infos = active.queryThreads(maxCount)
            val threads = infos.map { info ->
                HostDebuggerThreadSnapshot(
                    id = info.id,
                    name = info.name,
                    isMain = GdbRemoteThreadIdValidator.matchesTid(info.id, targetPid),
                )
            }
            val currentSelection = synchronized(lock) { mutable.selectedThreadId }
            val stopThread = synchronized(lock) {
                mutable.lastStopReply?.let(GdbRemoteStopReplyParser::threadId)
            }
            val selected =
                currentSelection?.takeIf { current -> threads.any { it.id == current } }
                    ?: threads.firstOrNull { it.isMain }?.id
                    ?: stopThread?.takeIf { stopped -> threads.any { it.id == stopped } }
                    ?: threads.firstOrNull()?.id
            if (selected != null) active.selectGeneralThread(selected)
            synchronized(lock) {
                mutable.threads = threads
                mutable.selectedThreadId = selected
                mutable.threadListCommandSent = true
                mutable.registers = emptyList()
                mutable.failure = null
            }
            appendAudit("threads_refreshed")
            snapshot()
        }

    suspend fun selectThread(threadId: String): HostDebuggerControlSnapshot =
        withContext(Dispatchers.IO) {
            requireStoppedClient()
            val normalized = GdbRemoteThreadIdValidator.normalize(threadId)
            val active = requireNotNull(client)
            synchronized(lock) {
                require(mutable.threads.any { it.id == normalized }) {
                    "只能选择刚刚由 LLDB 枚举并验证过的线程"
                }
            }
            active.selectGeneralThread(normalized)
            synchronized(lock) {
                mutable.selectedThreadId = normalized
                mutable.registers = emptyList()
                mutable.failure = null
            }
            appendAudit("thread_selected")
            snapshot()
        }

    suspend fun readRegisters(maxCount: Int = DEFAULT_UI_REGISTER_LIMIT): HostDebuggerControlSnapshot =
        withContext(Dispatchers.IO) {
            requireStoppedClient()
            require(maxCount in 1..MAX_UI_REGISTER_LIMIT) {
                "寄存器读取数量必须为 1..$MAX_UI_REGISTER_LIMIT"
            }
            val active = requireNotNull(client)
            val metadata = active.queryRegisters(maxCount)
            val values = metadata.map { info ->
                val value = active.readRegister(info.index)
                HostDebuggerRegisterSnapshot(
                    index = info.index,
                    name = info.name,
                    bitSize = info.bitSize,
                    rawHex = value.rawHex,
                )
            }
            synchronized(lock) {
                mutable.registers = values
                mutable.registerReadCommandSent = true
                mutable.failure = null
            }
            appendAudit("registers_read")
            snapshot()
        }

    suspend fun readMemory(address: Long, length: Int): HostDebuggerControlSnapshot =
        withContext(Dispatchers.IO) {
            requireStoppedClient()
            require(length in 1..MAX_UI_MEMORY_READ_BYTES) {
                "UI 单次内存读取限制为 1..$MAX_UI_MEMORY_READ_BYTES 字节"
            }
            val read = requireNotNull(client).readMemory(address, length)
            synchronized(lock) {
                mutable.lastMemoryAddress = read.address
                mutable.lastMemoryHex = read.hex
                mutable.memoryReadCommandSent = true
                mutable.failure = null
            }
            appendAudit("memory_read")
            snapshot()
        }

    suspend fun step(): HostDebuggerControlSnapshot = withContext(Dispatchers.IO) {
        requireStoppedClient()
        val activeClient = requireNotNull(client)
        val stepThreadId = synchronized(lock) {
            requireNotNull(mutable.selectedThreadId) {
                "尚未选择单步线程；请先刷新线程并选择一个 TID"
            }
        }
        synchronized(lock) {
            mutable.stepCommandSent = true
            mutable.stepCompleted = false
            mutable.stepAutoInterruptRecovered = false
            mutable.lastStepThreadId = stepThreadId
            mutable.targetRunning = true
            mutable.failure = null
        }
        appendAudit("step_start")
        recordTimeline(
            kind = "step_start",
            summary = "single-step requested",
            threadId = stepThreadId,
        )
        try {
            val stopReply = activeClient.step(stepThreadId)
            runCatching { activeClient.selectGeneralThread(stepThreadId) }
            val contextResult = runCatching {
                captureCodeContext(activeClient, stepThreadId, stopReply)
            }
            contextResult.getOrNull()?.let { context ->
                accountTrackedHardwareBreakpointHit(stopReply, context)
            }
            synchronized(lock) {
                mutable.lastStopReply = stopReply
                mutable.codeContext = contextResult.getOrNull()
                mutable.codeContextFailure = contextResult.exceptionOrNull()?.message
                mutable.stepCompleted = true
                mutable.targetRunning = false
                mutable.failure = null
            }
            appendAudit("step_stop")
            recordTimeline(
                kind = "step_stop",
                summary = "single-step completed normally",
                threadId = stepThreadId,
                codeContext = contextResult.getOrNull(),
            )
            snapshot()
        } catch (timeout: GdbRemoteRunTimeoutException) {
            appendAudit("step_wait_timeout_auto_interrupt_start")
            recordTimeline(
                kind = "step_timeout",
                summary = "single-step did not produce a stop within the bounded wait; automatic protocol interrupt recovery started",
                threadId = stepThreadId,
            )
            try {
                activeClient.interrupt()
                synchronized(lock) { mutable.interruptCommandSent = true }
                appendAudit("step_auto_interrupt_sent")

                val stopReply = activeClient.awaitStopAfterInterrupt()
                val recoveredThreadId = GdbRemoteStopReplyParser.threadId(stopReply) ?: stepThreadId
                runCatching { activeClient.selectGeneralThread(recoveredThreadId) }
                val recoveryContextResult = runCatching {
                    captureCodeContext(activeClient, recoveredThreadId, stopReply)
                }
                synchronized(lock) {
                    mutable.lastStopReply = stopReply
                    mutable.selectedThreadId = recoveredThreadId
                    mutable.codeContext = recoveryContextResult.getOrNull()
                    mutable.codeContextFailure = recoveryContextResult.exceptionOrNull()?.message
                    mutable.stepCompleted = false
                    mutable.stepAutoInterruptRecovered = true
                    mutable.targetRunning = false
                    mutable.failure = null
                }
                appendAudit("step_timeout_auto_interrupt_recovered")
                recordTimeline(
                    kind = "step_timeout_recovered_stop",
                    summary = "step did not complete; automatic interrupt recovered a trusted stop on the actual stopped thread",
                    threadId = recoveredThreadId,
                    codeContext = recoveryContextResult.getOrNull(),
                )
                snapshot()
            } catch (recovery: Exception) {
                recovery.addSuppressed(timeout)
                synchronized(lock) {
                    mutable.stepCompleted = false
                    mutable.targetRunning = true
                    mutable.failure =
                        "step 超时且自动 interrupt 恢复失败；目标状态仍未确认：${recovery.message ?: recovery::class.java.simpleName}"
                }
                appendAudit("step_timeout_auto_interrupt_recovery_failed")
                throw recovery
            }
        } catch (exception: Exception) {
            synchronized(lock) {
                mutable.stepCompleted = false
                mutable.targetRunning = false
                mutable.failure = exception.message ?: exception::class.java.simpleName
            }
            appendAudit("step_failed")
            throw exception
        }
    }

    suspend fun continueTarget(): HostDebuggerControlSnapshot {
        requireStoppedClient()
        synchronized(lock) {
            require(controlJob?.isActive != true) { "已有 continue/step 控制命令正在执行" }
            mutable.continueCommandSent = true
            mutable.targetRunning = true
            mutable.failure = null
        }
        appendAudit("continue_start")
        recordTimeline(
            kind = "continue_start",
            summary = "continue sent from known stopped state",
            threadId = synchronized(lock) { mutable.selectedThreadId },
        )
        val activeClient = requireNotNull(client)
        val job = scope.launch {
            runCatching { activeClient.continueUntilStop() }
                .onSuccess { stopReply ->
                    val stoppedThreadId = GdbRemoteStopReplyParser.threadId(stopReply)
                    if (stoppedThreadId != null) {
                        runCatching { activeClient.selectGeneralThread(stoppedThreadId) }
                    }

                    val autoAnchor = synchronized(lock) {
                        Triple(
                            mutable.autoAnchorAddress,
                            mutable.autoAnchorOwnsBreakpoint,
                            mutable.autoAnchorPrepared,
                        )
                    }
                    var autoRemoved = false
                    if (autoAnchor.third && autoAnchor.first != null && autoAnchor.second) {
                        val autoAddress = autoAnchor.first!!
                        autoRemoved = runCatching {
                            activeClient.removeHardwareExecutionBreakpoint(autoAddress)
                        }.isSuccess
                    }

                    val contextThreadId = stoppedThreadId ?: synchronized(lock) { mutable.selectedThreadId }
                    val contextResult = if (contextThreadId != null) {
                        runCatching { captureCodeContext(activeClient, contextThreadId, stopReply) }
                    } else {
                        Result.failure(IllegalStateException("stop reply 没有可用于代码上下文的线程"))
                    }
                    contextResult.getOrNull()?.let { context ->
                        accountTrackedHardwareBreakpointHit(stopReply, context)
                    }

                    synchronized(lock) {
                        mutable.lastStopReply = stopReply
                        if (stoppedThreadId != null) mutable.selectedThreadId = stoppedThreadId
                        mutable.codeContext = contextResult.getOrNull()
                        mutable.codeContextFailure = contextResult.exceptionOrNull()?.message
                        if (autoRemoved && autoAnchor.first != null) {
                            val address = autoAnchor.first!!
                            mutable.breakpoints = mutable.breakpoints.filterNot { it.address == address }
                            mutable.breakpointRemoveCommandSent = true
                        }
                        if (autoAnchor.third) {
                            mutable.autoAnchorPrepared = false
                            mutable.autoAnchorStopObserved = true
                            mutable.autoAnchorOwnsBreakpoint = false
                        }
                        mutable.targetRunning = false
                        mutable.failure = null
                    }
                    appendAudit(
                        if (autoAnchor.third) "continue_stop_auto_anchor_observed" else "continue_stop",
                    )
                    recordTimeline(
                        kind = if (autoAnchor.third) "auto_anchor_stop" else "continue_stop",
                        summary = if (autoAnchor.third) {
                            "one-shot anchor stop captured; breakpoint cleanup=${if (autoRemoved) "ok" else "not-owned-or-failed"}"
                        } else {
                            "continue produced a trusted stop"
                        },
                        threadId = contextThreadId,
                        codeContext = contextResult.getOrNull(),
                    )

                    val pauseRequestedBeforeAutoResume = synchronized(lock) {
                        if (mutable.pausePending) {
                            mutable.pausePending = false
                            true
                        } else {
                            false
                        }
                    }
                    if (autoAnchor.third && !pauseRequestedBeforeAutoResume) {
                        synchronized(lock) {
                            mutable.targetRunning = true
                            mutable.autoAnchorAutoResumed = true
                            mutable.failure = null
                        }
                        appendAudit("auto_anchor_context_captured_auto_resume_start")
                        recordTimeline(
                            kind = "auto_anchor_auto_resume",
                            summary = "anchor context captured; target automatically resumed so the target app remains interactive",
                            threadId = contextThreadId,
                            codeContext = contextResult.getOrNull(),
                        )

                        runCatching {
                            continueLiveAutoObservation(activeClient)
                        }.onFailure { exception ->
                            synchronized(lock) {
                                mutable.pausePending = false
                                mutable.targetRunning = false
                                mutable.failure = exception.message ?: exception::class.java.simpleName
                            }
                            appendAudit("auto_live_resume_failed")
                            recordTimeline(
                                kind = "auto_live_resume_failed",
                                summary = exception.message ?: exception::class.java.simpleName,
                            )
                        }
                    } else if (autoAnchor.third) {
                        synchronized(lock) { mutable.autoAnchorAutoResumed = false }
                        appendAudit("auto_anchor_stop_kept_for_explicit_pause")
                        recordTimeline(
                            kind = "auto_anchor_pause_stop",
                            summary = "explicit pause was already requested; anchor stop kept stopped instead of auto-resuming",
                            threadId = contextThreadId,
                            codeContext = contextResult.getOrNull(),
                        )
                    }
                }
                .onFailure { exception ->
                    synchronized(lock) {
                        // continue was only issued from a previously known stopped state. A direct
                        // E-response means the resume request itself was rejected, not a new stop.
                        mutable.targetRunning = false
                        mutable.failure = exception.message ?: exception::class.java.simpleName
                    }
                    appendAudit("continue_failed")
                }
        }
        synchronized(lock) { controlJob = job }
        return snapshot()
    }

    suspend fun interrupt(): HostDebuggerControlSnapshot = withContext(Dispatchers.IO) {
        val activeClient = synchronized(lock) {
            require(mutable.connected) { "LLDB client 未连接" }
            require(mutable.targetRunning) { "目标当前不是运行或状态未确认状态" }
            mutable.interruptCommandSent = true
            mutable.pausePending = true
            requireNotNull(client)
        }

        activeClient.interrupt()
        appendAudit("interrupt_sent")
        recordTimeline(
            kind = "manual_pause_requested",
            summary = "protocol interrupt requested; waiting for a trusted stop",
            threadId = synchronized(lock) { mutable.selectedThreadId },
        )

        val activeContinueJob = synchronized(lock) { controlJob?.takeIf { it.isActive } }
        if (activeContinueJob != null) {
            val completed = withTimeoutOrNull(INTERRUPT_WAIT_MILLIS) {
                joinAll(activeContinueJob)
                true
            } ?: false
            if (!completed) {
                synchronized(lock) {
                    mutable.failure = "interrupt 已发送，但 continue reader 尚未收到 stop reply；目标状态仍未确认"
                }
                appendAudit("interrupt_wait_continue_reader_timeout")
            }
        } else {
            // This is the recovery path for a synchronous step whose bounded reader timed out.
            // The step requestLock has already been released, so consume the stop reply generated
            // by the interrupt without sending any additional continue/step/raw packet.
            try {
                val stopReply = activeClient.awaitStopAfterInterrupt()
                synchronized(lock) {
                    mutable.lastStopReply = stopReply
                    mutable.targetRunning = false
                    mutable.failure = null
                }
                appendAudit("interrupt_stop_recovered")
            } catch (exception: Exception) {
                synchronized(lock) {
                    mutable.targetRunning = true
                    mutable.failure =
                        "interrupt 已发送，但仍未取得可信 stop reply：${exception.message ?: exception::class.java.simpleName}"
                }
                appendAudit("interrupt_recovery_failed_state_unresolved")
                throw exception
            }
        }
        snapshot()
    }

    suspend fun prepareForDetach(): HostDebuggerControlSnapshot = withContext(Dispatchers.IO) {
        val shouldInterrupt = synchronized(lock) { mutable.connected && mutable.targetRunning }
        if (shouldInterrupt) runCatching { interrupt() }

        val cleanupClient = synchronized(lock) {
            client?.takeIf { mutable.connected && !mutable.targetRunning }
        }
        val trackedBreakpoints = synchronized(lock) { mutable.breakpoints.toList() }
        if (cleanupClient != null && trackedBreakpoints.isNotEmpty()) {
            trackedBreakpoints.asReversed().forEach { breakpoint ->
                runCatching { cleanupClient.removeHardwareExecutionBreakpoint(breakpoint.address) }
            }
            synchronized(lock) { mutable.breakpoints = emptyList() }
            appendAudit("hardware_breakpoints_cleanup_before_detach")
        }

        synchronized(lock) {
            client?.close()
            client = null
            mutable.connected = false
            mutable.targetRunning = false
        }
        appendAudit("client_closed_for_detach")
        snapshot()
    }

    fun resetAfterDetach() {
        synchronized(lock) {
            client?.close()
            client = null
            controlJob = null
            mutable = MutableControlState()
        }
    }

    private suspend fun continueLiveAutoObservation(
        activeClient: HostDebuggerRemoteClient,
    ) {
        var stopReply = activeClient.continueUntilStop()
        while (true) {
            val stoppedThreadId = GdbRemoteStopReplyParser.threadId(stopReply)
            val manualPause = synchronized(lock) {
                val pending = mutable.pausePending
                if (pending) mutable.pausePending = false
                pending
            }

            if (manualPause) {
                if (stoppedThreadId != null) {
                    runCatching { activeClient.selectGeneralThread(stoppedThreadId) }
                }
                val contextResult = if (stoppedThreadId != null) {
                    runCatching { captureCodeContext(activeClient, stoppedThreadId, stopReply) }
                } else {
                    null
                }
                synchronized(lock) {
                    mutable.lastStopReply = stopReply
                    if (stoppedThreadId != null) mutable.selectedThreadId = stoppedThreadId
                    mutable.codeContext = contextResult?.getOrNull()
                    mutable.codeContextFailure = contextResult?.exceptionOrNull()?.message
                    mutable.targetRunning = false
                    mutable.failure = null
                }
                appendAudit("auto_live_pause_stop")
                recordTimeline(
                    kind = "manual_pause_stop",
                    summary = "manual pause/interrupt produced a trusted stop",
                    threadId = stoppedThreadId,
                    codeContext = contextResult?.getOrNull(),
                )
                return
            }

            if (stopReply.startsWith('W') || stopReply.startsWith('X')) {
                synchronized(lock) {
                    mutable.lastStopReply = stopReply
                    mutable.targetRunning = false
                    mutable.failure = null
                }
                appendAudit("auto_live_target_exit")
                recordTimeline(
                    kind = "auto_live_exit",
                    summary = "target exited while live observation was running: $stopReply",
                )
                return
            }

            val stoppedSignal = GdbRemoteStoppedSignalPassthrough.signalNumber(stopReply)
            if (
                stoppedSignal != null &&
                GdbRemoteStoppedSignalPassthrough.isAutoPassableTargetSignal(stopReply)
            ) {
                synchronized(lock) {
                    mutable.lastStopReply = stopReply
                    if (stoppedThreadId != null) mutable.selectedThreadId = stoppedThreadId
                    mutable.autoSignalPassthroughCount += 1
                    mutable.lastAutoPassedSignal = stoppedSignal
                    mutable.targetRunning = true
                    mutable.failure = null
                }
                appendAudit("auto_live_exact_thread_scoped_signal_passthrough")
                recordTimeline(
                    kind = "auto_signal_passthrough",
                    summary = "target signal 0x${stoppedSignal.toString(16).padStart(2, '0')} passed only to stopped thread=${stoppedThreadId ?: "unknown"}; all other threads plain-continued before heavy context capture",
                    threadId = stoppedThreadId,
                )
                stopReply = activeClient.continuePassingLastStoppedSignal()
                continue
            }

            if (stoppedThreadId != null) {
                runCatching { activeClient.selectGeneralThread(stoppedThreadId) }
            }
            val contextResult = if (stoppedThreadId != null) {
                runCatching { captureCodeContext(activeClient, stoppedThreadId, stopReply) }
            } else {
                null
            }
            contextResult?.getOrNull()?.let { context ->
                accountTrackedHardwareBreakpointHit(stopReply, context)
            }
            synchronized(lock) {
                mutable.lastStopReply = stopReply
                if (stoppedThreadId != null) mutable.selectedThreadId = stoppedThreadId
                mutable.codeContext = contextResult?.getOrNull()
                mutable.codeContextFailure = contextResult?.exceptionOrNull()?.message
                mutable.targetRunning = false
                mutable.failure = null
            }
            appendAudit("auto_live_debug_stop")
            recordTimeline(
                kind = "auto_live_debug_stop",
                summary = "live observation reached SIGTRAP or another non-pass-through debugger stop",
                threadId = stoppedThreadId,
                codeContext = contextResult?.getOrNull(),
            )
            return
        }
    }

    private fun recordTimeline(
        kind: String,
        summary: String,
        threadId: String? = null,
        codeContext: HostDebuggerCodeContextSnapshot? = null,
    ) {
        synchronized(lock) {
            mutable.timelineSequence += 1L
            val currentInstruction = codeContext?.instructions?.firstOrNull { it.current }
            val entry = HostDebuggerTimelineEntry(
                sequence = mutable.timelineSequence,
                timestampEpochMillis = System.currentTimeMillis(),
                kind = kind,
                threadId = threadId,
                pc = codeContext?.pc,
                modulePath = codeContext?.modulePath,
                moduleOffset = codeContext?.moduleOffset,
                instructionRawHex = currentInstruction?.rawHex,
                instructionText = currentInstruction?.text,
                summary = summary.replace('\n', ' ').take(TIMELINE_SUMMARY_MAX_CHARS),
            )
            mutable.timeline = (mutable.timeline + entry).takeLast(MAX_TIMELINE_ENTRIES)
        }
    }

    private suspend fun captureCodeContext(
        activeClient: HostDebuggerRemoteClient,
        threadId: String,
        stopReply: String,
    ): HostDebuggerCodeContextSnapshot {
        val normalizedThreadId = GdbRemoteThreadIdValidator.normalize(threadId)
        activeClient.selectGeneralThread(normalizedThreadId)

        val metadata = activeClient.queryRegisters(CODE_CONTEXT_REGISTER_METADATA_LIMIT)
        fun findRegister(generic: String, name: String, fallbackIndex: Int): GdbRemoteRegisterInfo =
            metadata.firstOrNull { it.genericName.equals(generic, ignoreCase = true) }
                ?: metadata.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: metadata.firstOrNull { it.index == fallbackIndex }
                ?: error("LLDB qRegisterInfo 缺少 $name 寄存器")

        val pcInfo = findRegister("pc", "pc", AARCH64_PC_REGISTER_FALLBACK_INDEX)
        val spInfo = findRegister("sp", "sp", AARCH64_SP_REGISTER_FALLBACK_INDEX)
        val lrInfo = metadata.firstOrNull { it.genericName.equals("ra", ignoreCase = true) }
            ?: metadata.firstOrNull { it.name.equals("lr", ignoreCase = true) }
            ?: metadata.firstOrNull { it.index == AARCH64_LR_REGISTER_FALLBACK_INDEX }
        val fpInfo = metadata.firstOrNull { it.genericName.equals("fp", ignoreCase = true) }
            ?: metadata.firstOrNull { it.name.equals("fp", ignoreCase = true) }
            ?: metadata.firstOrNull { it.name.equals("x29", ignoreCase = true) }
            ?: metadata.firstOrNull { it.index == AARCH64_FP_REGISTER_FALLBACK_INDEX }

        val pc = GdbRemoteHardwareBreakpointValidator.normalizeAddress(
            GdbRemoteRegisterValueDecoder.unsignedLittleEndianLong(activeClient.readRegister(pcInfo.index).rawHex),
        )
        val sp = runCatching {
            GdbRemoteRegisterValueDecoder.unsignedLittleEndianLong(activeClient.readRegister(spInfo.index).rawHex)
        }.getOrNull()
        val lr = lrInfo?.let { info ->
            runCatching {
                GdbRemoteRegisterValueDecoder.unsignedLittleEndianLong(activeClient.readRegister(info.index).rawHex)
            }.getOrNull()
        }
        val framePointer = fpInfo?.let { info ->
            runCatching {
                GdbRemoteRegisterValueDecoder.unsignedLittleEndianLong(activeClient.readRegister(info.index).rawHex)
            }.getOrNull()
        }

        val targetPid = synchronized(lock) { requireNotNull(mutable.pid) }
        val maps = readBridge.readProcessMaps(targetPid)
        require(maps.succeeded) {
            maps.stderr.ifBlank { maps.failure ?: "读取 /proc/$targetPid/maps 失败" }
        }
        val segments = HostDebuggerMemoryMapParser.parse(maps.stdout)
        val segment = HostDebuggerMemoryMapParser.findContaining(segments, pc)
            ?: error("PC 0x${pc.toString(16)} 不属于当前 /proc/$targetPid/maps 的任何映射")

        val desiredStart = maxOf(segment.startAddress, pc - minOf(pc, CODE_CONTEXT_BYTES_BEFORE.toLong()))
        var memoryStart = desiredStart - (desiredStart % AARCH64_INSTRUCTION_BYTES)
        if (memoryStart < segment.startAddress) memoryStart += AARCH64_INSTRUCTION_BYTES
        val desiredEnd = if (pc <= Long.MAX_VALUE - CODE_CONTEXT_BYTES_AFTER) {
            pc + CODE_CONTEXT_BYTES_AFTER
        } else {
            segment.endAddressExclusive
        }
        val memoryEnd = minOf(segment.endAddressExclusive, desiredEnd)
        val memoryLength = (((memoryEnd - memoryStart) / AARCH64_INSTRUCTION_BYTES) *
            AARCH64_INSTRUCTION_BYTES).toInt()
        require(memoryLength in AARCH64_INSTRUCTION_BYTES..CODE_CONTEXT_MAX_BYTES) {
            "PC 附近没有足够的 AArch64 指令字节可读取"
        }

        val memory = activeClient.readMemory(memoryStart, memoryLength)
        val instructions = Aarch64InstructionContextDecoder.decodeWindow(
            startAddress = memoryStart,
            bytes = memory.bytes,
            currentPc = pc,
        )
        require(instructions.any { it.current }) { "代码窗口没有包含当前 PC" }

        val stack = captureBoundedCallStack(
            activeClient = activeClient,
            pc = pc,
            lr = lr,
            sp = sp,
            framePointer = framePointer,
            segments = segments,
        )

        return HostDebuggerCodeContextSnapshot(
            threadId = normalizedThreadId,
            threadName = HostDebuggerStopReplyDetails.threadName(stopReply)
                ?: synchronized(lock) { mutable.threads.firstOrNull { it.id == normalizedThreadId }?.name },
            pc = pc,
            lr = lr,
            sp = sp,
            framePointer = framePointer,
            stack = stack,
            modulePath = segment.path.ifBlank { "<anonymous>" },
            moduleBase = segment.loadBase,
            moduleOffset = segment.relativeOffset(pc),
            segmentStart = segment.startAddress,
            segmentEndExclusive = segment.endAddressExclusive,
            segmentPermissions = segment.permissions,
            segmentFileOffset = segment.fileOffset,
            memoryStartAddress = memoryStart,
            memoryHex = memory.hex,
            instructions = instructions,
        )
    }

    private suspend fun captureBoundedCallStack(
        activeClient: HostDebuggerRemoteClient,
        pc: Long,
        lr: Long?,
        sp: Long?,
        framePointer: Long?,
        segments: List<HostDebuggerMemoryMapSegment>,
    ): HostDebuggerStackSnapshot {
        val frames = mutableListOf<HostDebuggerStackFrameSnapshot>()
        HostDebuggerStackFrameResolver.resolve(
            index = 0,
            address = pc,
            framePointer = framePointer,
            source = "program_counter",
            segments = segments,
        )?.let(frames::add)

        fun fallback(termination: String): HostDebuggerStackSnapshot {
            if (lr != null && frames.none { it.address == lr }) {
                HostDebuggerStackFrameResolver.resolve(
                    index = frames.size,
                    address = lr,
                    framePointer = framePointer,
                    source = "link_register_fallback",
                    segments = segments,
                )?.let(frames::add)
            }
            return HostDebuggerStackSnapshot(frames = frames, termination = termination, partial = true)
        }

        val initialFramePointer = framePointer ?: return fallback("frame_pointer_unavailable")
        Aarch64FrameRecordDecoder.validateFramePointer(initialFramePointer, sp, segments)?.let { reason ->
            return fallback(reason)
        }

        var currentFramePointer = initialFramePointer
        val seen = mutableSetOf<Long>()
        while (frames.size < MAX_CALL_STACK_FRAMES) {
            if (!seen.add(currentFramePointer)) {
                return HostDebuggerStackSnapshot(frames, "frame_pointer_cycle", partial = true)
            }
            val record = runCatching {
                Aarch64FrameRecordDecoder.decode(
                    activeClient.readMemory(
                        currentFramePointer,
                        Aarch64FrameRecordDecoder.FRAME_RECORD_BYTES,
                    ).bytes,
                )
            }.getOrElse {
                return HostDebuggerStackSnapshot(frames, "frame_record_read_failed", partial = true)
            }

            if (record.savedLinkRegister == 0L) {
                return HostDebuggerStackSnapshot(frames, "saved_link_register_zero", partial = false)
            }
            val caller = HostDebuggerStackFrameResolver.resolve(
                index = frames.size,
                address = record.savedLinkRegister,
                framePointer = currentFramePointer,
                source = "aarch64_fp_chain",
                segments = segments,
            ) ?: return HostDebuggerStackSnapshot(frames, "saved_link_register_not_executable", partial = true)
            if (frames.none { it.address == caller.address }) frames += caller

            if (record.previousFramePointer == 0L) {
                return HostDebuggerStackSnapshot(frames, "frame_chain_terminated", partial = false)
            }
            Aarch64FrameRecordDecoder.validateNextFramePointer(
                currentFramePointer = currentFramePointer,
                nextFramePointer = record.previousFramePointer,
                stackPointer = sp,
                segments = segments,
            )?.let { reason ->
                return HostDebuggerStackSnapshot(frames, reason, partial = true)
            }
            currentFramePointer = record.previousFramePointer
        }
        return HostDebuggerStackSnapshot(frames, "frame_limit_reached", partial = true)
    }

    private fun accountTrackedHardwareBreakpointHit(
        stopReply: String,
        context: HostDebuggerCodeContextSnapshot,
    ) {
        synchronized(lock) {
            mutable.breakpoints = HostDebuggerBreakpointHitAccounting.applyTrustedStop(
                breakpoints = mutable.breakpoints,
                stopReply = stopReply,
                context = context,
                timestampEpochMillis = System.currentTimeMillis(),
            )
        }
    }

    private suspend fun awaitConfirmedAttach(sessionId: String): HostDebuggerSessionSnapshot? {
        var latest: HostDebuggerSessionSnapshot? = null
        repeat(POST_ATTACH_CONFIRM_ATTEMPTS) {
            latest = manager.refresh()
            val current = latest
            if (current == null || current.sessionId != sessionId || !current.running || current.failure != null) {
                return current
            }
            if (current.attachedObserved && current.tracerPidCurrent != null && current.tracerPidCurrent > 0) {
                return current
            }
            delay(POST_ATTACH_CONFIRM_DELAY_MILLIS)
        }
        return latest
    }

    private fun requireStoppedClient() {
        synchronized(lock) {
            require(mutable.controlAuthorizationVerified) { "尚未完成 CONTROL 精确授权" }
            require(mutable.connected && client?.connected == true) { "LLDB client 未连接" }
            require(!mutable.targetRunning) {
                "目标正在运行或状态未确认；请先 interrupt 恢复可信 stop reply"
            }
        }
    }

    private suspend fun appendAudit(event: String) = withContext(Dispatchers.IO) {
        val record = synchronized(lock) {
            JSONObject()
                .put("schemaVersion", 1)
                .put("timestampEpochMillis", System.currentTimeMillis())
                .put("event", event)
                .put("sessionId", mutable.sessionId ?: JSONObject.NULL)
                .put("packageName", mutable.packageName ?: JSONObject.NULL)
                .put("pid", mutable.pid ?: JSONObject.NULL)
                .put("port", mutable.port ?: JSONObject.NULL)
                .put("networkScope", "127.0.0.1 only")
                .put("controlAuthorizationVerified", mutable.controlAuthorizationVerified)
                .put("clientConnected", mutable.connected)
                .put("targetRunning", mutable.targetRunning)
                .put("lastStopReply", mutable.lastStopReply ?: JSONObject.NULL)
                .put("threadListCommandSent", mutable.threadListCommandSent)
                .put("threadCount", mutable.threads.size)
                .put("selectedThreadId", mutable.selectedThreadId ?: JSONObject.NULL)
                .put("lastStepThreadId", mutable.lastStepThreadId ?: JSONObject.NULL)
                .put("stepCompleted", mutable.stepCompleted)
                .put("continueCommandSent", mutable.continueCommandSent)
                .put("stepCommandSent", mutable.stepCommandSent)
                .put("stepAutoInterruptRecovered", mutable.stepAutoInterruptRecovered)
                .put("interruptCommandSent", mutable.interruptCommandSent)
                .put("registerReadCommandSent", mutable.registerReadCommandSent)
                .put("memoryReadCommandSent", mutable.memoryReadCommandSent)
                .put("registerWriteCommandSent", false)
                .put("memoryWriteCommandSent", false)
                .put("breakpointCommandSent", mutable.breakpointCommandSent)
                .put("breakpointSetCommandSent", mutable.breakpointSetCommandSent)
                .put("breakpointRemoveCommandSent", mutable.breakpointRemoveCommandSent)
                .put("breakpointCount", mutable.breakpoints.size)
                .put("breakpointHitCount", mutable.breakpoints.sumOf { it.hitCount })
                .put("breakpointMode", "hardware_execution_only")
                .put("autoAnchorPrepared", mutable.autoAnchorPrepared)
                .put("autoAnchorThreadId", mutable.autoAnchorThreadId ?: JSONObject.NULL)
                .put("autoAnchorAddress", mutable.autoAnchorAddress ?: JSONObject.NULL)
                .put("autoAnchorStopObserved", mutable.autoAnchorStopObserved)
                .put("codeContextCaptured", mutable.codeContext != null)
                .put("codeContextPc", mutable.codeContext?.pc ?: JSONObject.NULL)
                .put("codeContextModule", mutable.codeContext?.modulePath ?: JSONObject.NULL)
                .put("codeContextFailure", mutable.codeContextFailure ?: JSONObject.NULL)
                .put("callStackFrameCount", mutable.codeContext?.stack?.frames?.size ?: 0)
                .put("callStackTermination", mutable.codeContext?.stack?.termination ?: JSONObject.NULL)
                .put("callStackPartial", mutable.codeContext?.stack?.partial ?: JSONObject.NULL)
                .put("autoAnchorAutoResumed", mutable.autoAnchorAutoResumed)
                .put("autoSignalPassthroughCount", mutable.autoSignalPassthroughCount)
                .put("lastAutoPassedSignal", mutable.lastAutoPassedSignal ?: JSONObject.NULL)
                .put("autoSignalPassthroughScope", "stopped_thread_only")
                .put("explicitSignalReasonTrapPassthrough", true)
                .put("timelineCount", mutable.timeline.size)
                .put("timelineLastKind", mutable.timeline.lastOrNull()?.kind ?: JSONObject.NULL)
                .put("rawPacketAdapterExposed", false)
                .put("failure", mutable.failure ?: JSONObject.NULL)
        }
        synchronized(AUDIT_LOCK) {
            auditFile.parentFile?.mkdirs()
            auditFile.appendText(record.toString() + "\n", Charsets.UTF_8)
        }
    }

    private fun snapshotLocked() = HostDebuggerControlSnapshot(
        sessionId = mutable.sessionId,
        packageName = mutable.packageName,
        pid = mutable.pid,
        port = mutable.port,
        connected = mutable.connected,
        controlAuthorizationVerified = mutable.controlAuthorizationVerified,
        targetRunning = mutable.targetRunning,
        lastStopReply = mutable.lastStopReply,
        capabilities = mutable.capabilities,
        threads = mutable.threads,
        selectedThreadId = mutable.selectedThreadId,
        lastStepThreadId = mutable.lastStepThreadId,
        stepCompleted = mutable.stepCompleted,
        threadListCommandSent = mutable.threadListCommandSent,
        breakpoints = mutable.breakpoints,
        breakpointSetCommandSent = mutable.breakpointSetCommandSent,
        breakpointRemoveCommandSent = mutable.breakpointRemoveCommandSent,
        autoAnchorPrepared = mutable.autoAnchorPrepared,
        autoAnchorThreadId = mutable.autoAnchorThreadId,
        autoAnchorAddress = mutable.autoAnchorAddress,
        autoAnchorStopObserved = mutable.autoAnchorStopObserved,
        codeContext = mutable.codeContext,
        codeContextFailure = mutable.codeContextFailure,
        autoAnchorAutoResumed = mutable.autoAnchorAutoResumed,
        autoSignalPassthroughCount = mutable.autoSignalPassthroughCount,
        lastAutoPassedSignal = mutable.lastAutoPassedSignal,
        timeline = mutable.timeline,
        registers = mutable.registers,
        lastMemoryAddress = mutable.lastMemoryAddress,
        lastMemoryHex = mutable.lastMemoryHex,
        continueCommandSent = mutable.continueCommandSent,
        stepCommandSent = mutable.stepCommandSent,
        stepAutoInterruptRecovered = mutable.stepAutoInterruptRecovered,
        interruptCommandSent = mutable.interruptCommandSent,
        registerReadCommandSent = mutable.registerReadCommandSent,
        memoryReadCommandSent = mutable.memoryReadCommandSent,
        registerWriteCommandSent = false,
        memoryWriteCommandSent = false,
        breakpointCommandSent = mutable.breakpointCommandSent,
        failure = mutable.failure,
    )

    private data class MutableControlState(
        var sessionId: String? = null,
        var packageName: String? = null,
        var pid: Int? = null,
        var port: Int? = null,
        var connected: Boolean = false,
        var controlAuthorizationVerified: Boolean = false,
        var targetRunning: Boolean = false,
        var lastStopReply: String? = null,
        var capabilities: List<String> = emptyList(),
        var threads: List<HostDebuggerThreadSnapshot> = emptyList(),
        var selectedThreadId: String? = null,
        var lastStepThreadId: String? = null,
        var stepCompleted: Boolean = false,
        var threadListCommandSent: Boolean = false,
        var breakpoints: List<HostDebuggerBreakpointSnapshot> = emptyList(),
        var breakpointCommandSent: Boolean = false,
        var breakpointSetCommandSent: Boolean = false,
        var breakpointRemoveCommandSent: Boolean = false,
        var autoAnchorPrepared: Boolean = false,
        var autoAnchorThreadId: String? = null,
        var autoAnchorAddress: Long? = null,
        var autoAnchorOwnsBreakpoint: Boolean = false,
        var autoAnchorStopObserved: Boolean = false,
        var codeContext: HostDebuggerCodeContextSnapshot? = null,
        var codeContextFailure: String? = null,
        var autoAnchorAutoResumed: Boolean = false,
        var autoSignalPassthroughCount: Int = 0,
        var lastAutoPassedSignal: Int? = null,
        var pausePending: Boolean = false,
        var timelineSequence: Long = 0L,
        var timeline: List<HostDebuggerTimelineEntry> = emptyList(),
        var registers: List<HostDebuggerRegisterSnapshot> = emptyList(),
        var lastMemoryAddress: Long? = null,
        var lastMemoryHex: String? = null,
        var continueCommandSent: Boolean = false,
        var stepCommandSent: Boolean = false,
        var stepAutoInterruptRecovered: Boolean = false,
        var interruptCommandSent: Boolean = false,
        var registerReadCommandSent: Boolean = false,
        var memoryReadCommandSent: Boolean = false,
        var failure: String? = null,
    )

    companion object {
        const val DEFAULT_UI_REGISTER_LIMIT = 32
        const val MAX_UI_REGISTER_LIMIT = 128
        const val DEFAULT_UI_THREAD_LIMIT = 128
        const val MAX_UI_THREAD_LIMIT = 256
        const val MAX_UI_HARDWARE_BREAKPOINTS = 8
        const val AUTO_REGISTER_METADATA_LIMIT = 64
        const val AARCH64_PC_REGISTER_FALLBACK_INDEX = 32
        const val CODE_CONTEXT_REGISTER_METADATA_LIMIT = 64
        const val AARCH64_LR_REGISTER_FALLBACK_INDEX = 30
        const val AARCH64_FP_REGISTER_FALLBACK_INDEX = 29
        const val AARCH64_SP_REGISTER_FALLBACK_INDEX = 31
        const val AARCH64_INSTRUCTION_BYTES = 4
        const val CODE_CONTEXT_BYTES_BEFORE = 16
        const val CODE_CONTEXT_BYTES_AFTER = 20L
        const val CODE_CONTEXT_MAX_BYTES = 36
        const val MAX_CALL_STACK_FRAMES = 16
        const val MAX_TIMELINE_ENTRIES = 64
        const val TIMELINE_SUMMARY_MAX_CHARS = 240
        const val MAX_UI_MEMORY_READ_BYTES = 512
        private const val POST_ATTACH_CONFIRM_ATTEMPTS = 30
        private const val POST_ATTACH_CONFIRM_DELAY_MILLIS = 100L
        private const val INTERRUPT_WAIT_MILLIS = 5_000L
        private val AUDIT_LOCK = Any()
    }
}
