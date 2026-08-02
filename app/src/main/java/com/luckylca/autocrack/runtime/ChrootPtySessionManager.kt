package com.luckylca.autocrack.runtime

import android.content.Context
import android.system.OsConstants
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

class ChrootPtySessionManager private constructor(context: Context) {
    private data class ManagedSession(
        val info: ActivePtySession,
        val transcriptFile: File,
        val finalized: AtomicBoolean = AtomicBoolean(false),
    )

    private val appContext = context.applicationContext
    private val layout = RuntimeLayout(appContext).initialize()
    private val hostEngine = RootShellRuntimeEngine(layout)
    private val chrootEngine = ChrootRuntimeEngine(layout, hostEngine)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()
    private val auditLock = Any()
    private val outputLock = Any()
    private val outputBuffer = TerminalOutputBuffer()
    private val ptyAuditFile = File(layout.auditRoot, "pty-sessions.jsonl")

    @Volatile
    private var activeSession: ManagedSession? = null
    private var readerJob: Job? = null

    private val mutableSnapshot = MutableStateFlow(
        PtySessionSnapshot(auditPath = ptyAuditFile.path),
    )
    val snapshot: StateFlow<PtySessionSnapshot> = mutableSnapshot.asStateFlow()

    suspend fun open(
        rows: Int = DEFAULT_TERMINAL_ROWS,
        columns: Int = DEFAULT_TERMINAL_COLUMNS,
    ) {
        require(rows in MIN_TERMINAL_SIZE..MAX_TERMINAL_SIZE) { "终端行数非法" }
        require(columns in MIN_TERMINAL_SIZE..MAX_TERMINAL_SIZE) { "终端列数非法" }

        lifecycleMutex.withLock {
            check(activeSession == null) { "已有 PTY 会话正在运行" }
            check(layout.readRootfsState() == RuntimeRootfsState.INSTALLED) {
                "Debian rootfs 尚未安装"
            }

            synchronized(outputLock) { outputBuffer.clear() }
            mutableSnapshot.value = PtySessionSnapshot(
                state = PtySessionState.STARTING,
                rows = rows,
                columns = columns,
                auditPath = ptyAuditFile.path,
            )

            val mountResult = chrootEngine.prepareMounts(layout.createRuntimeWorkspace())
            check(mountResult.succeeded) {
                "准备 PTY chroot 挂载失败：exit=${mountResult.exitCode}, ${mountResult.stderr}"
            }

            try {
                val command = ChrootPtyCommandBuilder.build(layout.rootfsRoot.path)
                val handle = withContext(Dispatchers.IO) {
                    NativePtyBridge.nativeOpen(
                        program = ROOT_SU_PATH,
                        arguments = arrayOf("-c", command),
                        rows = rows,
                        columns = columns,
                    )
                }
                check(handle > 0L) { "Native PTY 创建失败：errno=${-handle}" }
                val pid = NativePtyBridge.nativePid(handle)
                check(pid > 0) {
                    NativePtyBridge.nativeClose(handle, OsConstants.SIGKILL)
                    "Native PTY 未返回有效 PID"
                }

                val openedAt = System.currentTimeMillis()
                val sessionId = "pty-$openedAt-$pid"
                val transcript = File(layout.sessionsRoot, "$sessionId.log").apply {
                    parentFile?.mkdirs()
                    writeText("", Charsets.UTF_8)
                }
                val managed = ManagedSession(
                    info = ActivePtySession(
                        sessionId = sessionId,
                        handle = handle,
                        pid = pid,
                        openedAtEpochMillis = openedAt,
                        transcriptPath = transcript.path,
                        rows = rows,
                        columns = columns,
                    ),
                    transcriptFile = transcript,
                )
                activeSession = managed
                mutableSnapshot.value = PtySessionSnapshot(
                    sessionId = sessionId,
                    state = PtySessionState.RUNNING,
                    pid = pid,
                    rows = rows,
                    columns = columns,
                    openedAtEpochMillis = openedAt,
                    transcriptPath = transcript.path,
                    auditPath = ptyAuditFile.path,
                )
                appendAudit(
                    event = "open",
                    session = managed,
                    detail = JSONObject()
                        .put("rows", rows)
                        .put("columns", columns)
                        .put("rootfsVersion", layout.readRootfsVersion() ?: JSONObject.NULL),
                )
                PtySessionForegroundService.start(appContext, sessionId, pid)
                readerJob = scope.launch { readLoop(managed) }
            } catch (exception: Exception) {
                runCatching { chrootEngine.cleanupMounts() }
                mutableSnapshot.update {
                    it.copy(
                        state = PtySessionState.FAILED,
                        completedAtEpochMillis = System.currentTimeMillis(),
                        failure = exception.message ?: exception::class.java.name,
                    )
                }
                throw exception
            }
        }
    }

    suspend fun send(text: String): Int {
        require(text.isNotEmpty()) { "PTY 输入不能为空" }
        val session = activeSession ?: error("没有运行中的 PTY 会话")
        check(mutableSnapshot.value.state == PtySessionState.RUNNING) { "PTY 会话不可写" }
        val bytes = text.toByteArray(Charsets.UTF_8)
        val written = withContext(Dispatchers.IO) {
            NativePtyBridge.nativeWrite(session.info.handle, bytes)
        }
        check(written >= 0) { "PTY 写入失败：errno=${-written}" }
        mutableSnapshot.update { current ->
            current.copy(bytesWritten = current.bytesWritten + written)
        }
        appendAudit(
            event = "write",
            session = session,
            detail = JSONObject().put("bytes", written),
        )
        return written
    }

    suspend fun sendLine(line: String): Int = send(line + "\n")

    suspend fun interrupt(): Boolean {
        val session = activeSession ?: return false
        val written = withContext(Dispatchers.IO) {
            NativePtyBridge.nativeWrite(session.info.handle, byteArrayOf(CTRL_C))
        }
        val succeeded = written == 1
        appendAudit(
            event = "interrupt",
            session = session,
            detail = JSONObject().put("succeeded", succeeded),
        )
        return succeeded
    }

    suspend fun resize(rows: Int, columns: Int): Boolean {
        require(rows in MIN_TERMINAL_SIZE..MAX_TERMINAL_SIZE) { "终端行数非法" }
        require(columns in MIN_TERMINAL_SIZE..MAX_TERMINAL_SIZE) { "终端列数非法" }
        val session = activeSession ?: return false
        val succeeded = withContext(Dispatchers.IO) {
            NativePtyBridge.nativeResize(session.info.handle, rows, columns)
        }
        if (succeeded) {
            mutableSnapshot.update { it.copy(rows = rows, columns = columns) }
        }
        appendAudit(
            event = "resize",
            session = session,
            detail = JSONObject()
                .put("rows", rows)
                .put("columns", columns)
                .put("succeeded", succeeded),
        )
        return succeeded
    }

    suspend fun close() {
        val session = lifecycleMutex.withLock {
            val current = activeSession ?: return
            mutableSnapshot.update { it.copy(state = PtySessionState.CLOSING) }
            current
        }
        val exitCode = withContext(Dispatchers.IO) {
            NativePtyBridge.nativeClose(session.info.handle, OsConstants.SIGHUP)
        }
        finalizeSession(session, exitCode, null)
    }

    fun clearOutput() {
        synchronized(outputLock) { outputBuffer.clear() }
        mutableSnapshot.update {
            it.copy(output = "", outputVersion = it.outputVersion + 1L)
        }
    }

    fun diagnostics(): String {
        val current = snapshot.value
        return buildString {
            appendLine("AutoCrackApp Native PTY 诊断")
            appendLine("状态：${current.state}")
            appendLine("会话：${current.sessionId ?: "无"}")
            appendLine("PID：${current.pid ?: "无"}")
            appendLine("终端：${current.rows}x${current.columns}")
            appendLine("开始：${current.openedAtEpochMillis ?: "无"}")
            appendLine("完成：${current.completedAtEpochMillis ?: "无"}")
            appendLine("退出码：${current.exitCode ?: "无"}")
            appendLine("读取字节：${current.bytesRead}")
            appendLine("写入字节：${current.bytesWritten}")
            appendLine("Transcript：${current.transcriptPath ?: "无"}")
            appendLine("审计：${current.auditPath ?: "无"}")
            appendLine("Failure：${current.failure ?: "无"}")
            appendLine()
            appendLine("终端输出：")
            appendLine(current.output.takeLast(MAX_DIAGNOSTIC_OUTPUT_CHARS))
        }
    }

    private suspend fun readLoop(session: ManagedSession) {
        var failure: String? = null
        try {
            while (NativePtyBridge.nativeIsAlive(session.info.handle)) {
                val bytes = NativePtyBridge.nativeRead(
                    session.info.handle,
                    READ_CHUNK_BYTES,
                    READ_POLL_MILLIS,
                ) ?: break
                if (bytes.isNotEmpty()) appendOutput(session, bytes)
            }
            while (true) {
                val bytes = NativePtyBridge.nativeRead(
                    session.info.handle,
                    READ_CHUNK_BYTES,
                    FINAL_DRAIN_POLL_MILLIS,
                ) ?: break
                if (bytes.isEmpty()) break
                appendOutput(session, bytes)
            }
        } catch (exception: Exception) {
            failure = exception.message ?: exception::class.java.name
        }

        var exitCode = NativePtyBridge.nativeWait(session.info.handle, PROCESS_WAIT_MILLIS)
        if (exitCode == NativePtyBridge.STILL_RUNNING) {
            exitCode = NativePtyBridge.nativeClose(session.info.handle, OsConstants.SIGHUP)
        } else {
            NativePtyBridge.nativeClose(session.info.handle, 0)
        }
        finalizeSession(session, exitCode, failure)
    }

    private fun appendOutput(session: ManagedSession, bytes: ByteArray) {
        session.transcriptFile.appendBytes(bytes)
        val decoded = bytes.toString(Charsets.UTF_8)
        val visible = synchronized(outputLock) { outputBuffer.append(decoded) }
        mutableSnapshot.update { current ->
            current.copy(
                output = visible,
                outputVersion = current.outputVersion + 1L,
                bytesRead = current.bytesRead + bytes.size,
            )
        }
    }

    private suspend fun finalizeSession(
        session: ManagedSession,
        exitCode: Int,
        failure: String?,
    ) {
        if (!session.finalized.compareAndSet(false, true)) return
        readerJob?.cancel()
        readerJob = null
        runCatching { chrootEngine.cleanupMounts() }
        PtySessionForegroundService.stop(appContext)

        lifecycleMutex.withLock {
            if (activeSession === session) activeSession = null
        }
        val completedAt = System.currentTimeMillis()
        val finalState = if (failure == null) PtySessionState.EXITED else PtySessionState.FAILED
        mutableSnapshot.update { current ->
            current.copy(
                state = finalState,
                completedAtEpochMillis = completedAt,
                exitCode = exitCode.takeUnless { it == NativePtyBridge.STILL_RUNNING },
                failure = failure,
            )
        }
        appendAudit(
            event = "close",
            session = session,
            detail = JSONObject()
                .put("exitCode", exitCode)
                .put("failure", failure ?: JSONObject.NULL)
                .put("completedAtEpochMillis", completedAt),
        )
    }

    private fun appendAudit(
        event: String,
        session: ManagedSession,
        detail: JSONObject,
    ) {
        val json = JSONObject()
            .put("schemaVersion", 1)
            .put("event", event)
            .put("sessionId", session.info.sessionId)
            .put("pid", session.info.pid)
            .put("timestampEpochMillis", System.currentTimeMillis())
            .put("detail", detail)
        synchronized(auditLock) {
            ptyAuditFile.parentFile?.mkdirs()
            ptyAuditFile.appendText(json.toString() + "\n", Charsets.UTF_8)
        }
    }

    companion object {
        @Volatile
        private var instance: ChrootPtySessionManager? = null

        fun get(context: Context): ChrootPtySessionManager = instance ?: synchronized(this) {
            instance ?: ChrootPtySessionManager(context).also { instance = it }
        }

        private const val ROOT_SU_PATH = "/system/bin/su"
        private const val READ_CHUNK_BYTES = 16 * 1024
        private const val READ_POLL_MILLIS = 200
        private const val FINAL_DRAIN_POLL_MILLIS = 50
        private const val PROCESS_WAIT_MILLIS = 1_000
        private const val MIN_TERMINAL_SIZE = 2
        private const val MAX_TERMINAL_SIZE = 1_000
        private const val MAX_DIAGNOSTIC_OUTPUT_CHARS = 40_000
        private const val CTRL_C: Byte = 0x03
    }
}
