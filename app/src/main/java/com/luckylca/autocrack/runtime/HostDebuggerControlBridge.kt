package com.luckylca.autocrack.runtime

import com.luckylca.autocrack.apk.PackageOutputParser
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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

data class HostDebuggerRegisterSnapshot(
    val index: Int,
    val name: String,
    val bitSize: Int?,
    val rawHex: String,
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
    val registers: List<HostDebuggerRegisterSnapshot>,
    val lastMemoryAddress: Long?,
    val lastMemoryHex: String?,
    val continueCommandSent: Boolean,
    val stepCommandSent: Boolean,
    val interruptCommandSent: Boolean,
    val registerReadCommandSent: Boolean,
    val memoryReadCommandSent: Boolean,
    val registerWriteCommandSent: Boolean,
    val memoryWriteCommandSent: Boolean,
    val breakpointCommandSent: Boolean,
    val failure: String?,
)

/**
 * Confirmation-gated client bridge for the loopback lldb-server created by
 * [HostDebuggerSessionManager]. This bridge does not provide arbitrary packet execution.
 */
class HostDebuggerControlBridge(
    private val manager: HostDebuggerSessionManager,
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
            require(server.running) { "LLDB server 未运行" }
            require(server.attachedObserved && server.tracerPidCurrent != null && server.tracerPidCurrent != 0) {
                "尚未确认 LLDB server 已 attach，拒绝建立控制客户端"
            }
            HostDebuggerControlAuthorization.requireAuthorized(
                server.packageName,
                server.pid,
                authorizationPhrase,
            )

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
            try {
                val handshake = created.connect()
                synchronized(lock) {
                    client = created
                    mutable.connected = true
                    mutable.lastStopReply = handshake.stopReply
                    mutable.capabilities = handshake.capabilities.sorted()
                    mutable.targetRunning = false
                }
                appendAudit("client_connected")
                snapshot()
            } catch (exception: Exception) {
                created.close()
                synchronized(lock) {
                    mutable.failure = exception.message ?: exception::class.java.simpleName
                }
                appendAudit("client_connect_failed")
                throw exception
            }
        }

    fun snapshot(): HostDebuggerControlSnapshot = synchronized(lock) { snapshotLocked() }

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
        synchronized(lock) {
            mutable.stepCommandSent = true
            mutable.targetRunning = true
            mutable.failure = null
        }
        appendAudit("step_start")
        try {
            val stopReply = requireNotNull(client).step()
            synchronized(lock) {
                mutable.lastStopReply = stopReply
                mutable.targetRunning = false
            }
            appendAudit("step_stop")
            snapshot()
        } catch (exception: Exception) {
            synchronized(lock) {
                mutable.targetRunning = false
                mutable.failure = exception.message ?: exception::class.java.simpleName
            }
            appendAudit("step_failed")
            throw exception
        }
    }

    /** Start continue asynchronously so the UI remains able to issue [interrupt]. */
    suspend fun continueTarget(): HostDebuggerControlSnapshot {
        requireStoppedClient()
        synchronized(lock) {
            require(controlJob?.isActive != true) { "已有 continue/step 控制命令正在执行" }
            mutable.continueCommandSent = true
            mutable.targetRunning = true
            mutable.failure = null
        }
        appendAudit("continue_start")
        val activeClient = requireNotNull(client)
        val job = scope.launch {
            runCatching { activeClient.continueUntilStop() }
                .onSuccess { stopReply ->
                    synchronized(lock) {
                        mutable.lastStopReply = stopReply
                        mutable.targetRunning = false
                    }
                    appendAudit("continue_stop")
                }
                .onFailure { exception ->
                    synchronized(lock) {
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
            require(mutable.targetRunning) { "目标当前不是 continue 运行状态" }
            mutable.interruptCommandSent = true
            requireNotNull(client)
        }
        activeClient.interrupt()
        appendAudit("interrupt_sent")
        val job = synchronized(lock) { controlJob }
        if (job != null) {
            withTimeoutOrNull(INTERRUPT_WAIT_MILLIS) { joinAll(job) }
        }
        snapshot()
    }

    /**
     * Prepare for the existing trusted helper teardown. If the target is running through a
     * continue command, interrupt it first; then close only the loopback client socket.
     */
    suspend fun prepareForDetach(): HostDebuggerControlSnapshot = withContext(Dispatchers.IO) {
        val shouldInterrupt = synchronized(lock) { mutable.connected && mutable.targetRunning }
        if (shouldInterrupt) {
            runCatching { interrupt() }
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

    private fun requireStoppedClient() {
        synchronized(lock) {
            require(mutable.controlAuthorizationVerified) { "尚未完成 CONTROL 精确授权" }
            require(mutable.connected && client?.connected == true) { "LLDB client 未连接" }
            require(!mutable.targetRunning) { "目标正在运行；请先 interrupt 后再读取状态" }
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
                .put("continueCommandSent", mutable.continueCommandSent)
                .put("stepCommandSent", mutable.stepCommandSent)
                .put("interruptCommandSent", mutable.interruptCommandSent)
                .put("registerReadCommandSent", mutable.registerReadCommandSent)
                .put("memoryReadCommandSent", mutable.memoryReadCommandSent)
                .put("registerWriteCommandSent", false)
                .put("memoryWriteCommandSent", false)
                .put("breakpointCommandSent", false)
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
        registers = mutable.registers,
        lastMemoryAddress = mutable.lastMemoryAddress,
        lastMemoryHex = mutable.lastMemoryHex,
        continueCommandSent = mutable.continueCommandSent,
        stepCommandSent = mutable.stepCommandSent,
        interruptCommandSent = mutable.interruptCommandSent,
        registerReadCommandSent = mutable.registerReadCommandSent,
        memoryReadCommandSent = mutable.memoryReadCommandSent,
        registerWriteCommandSent = false,
        memoryWriteCommandSent = false,
        breakpointCommandSent = false,
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
        var registers: List<HostDebuggerRegisterSnapshot> = emptyList(),
        var lastMemoryAddress: Long? = null,
        var lastMemoryHex: String? = null,
        var continueCommandSent: Boolean = false,
        var stepCommandSent: Boolean = false,
        var interruptCommandSent: Boolean = false,
        var registerReadCommandSent: Boolean = false,
        var memoryReadCommandSent: Boolean = false,
        var failure: String? = null,
    )

    companion object {
        const val DEFAULT_UI_REGISTER_LIMIT = 32
        const val MAX_UI_REGISTER_LIMIT = 128
        const val MAX_UI_MEMORY_READ_BYTES = 512
        private const val INTERRUPT_WAIT_MILLIS = 5_000L
        private val AUDIT_LOCK = Any()
    }
}
