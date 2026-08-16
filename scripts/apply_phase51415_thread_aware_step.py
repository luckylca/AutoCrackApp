#!/usr/bin/env python3
from pathlib import Path


def replace_exact(path: str, old: str, new: str, expected: int = 1) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    actual = text.count(old)
    if actual != expected:
        raise SystemExit(
            f"{path}: expected {expected} occurrence(s) of {old!r}, found {actual}"
        )
    p.write_text(text.replace(old, new), encoding="utf-8")


gradle = "app/build.gradle.kts"
replace_exact(gradle, "versionCode = 49", "versionCode = 50")
replace_exact(
    gradle,
    'versionName = "0.5.14.14-phase5.14-bounded-step-recovery"',
    'versionName = "0.5.14.15-phase5.14-thread-aware-step"',
)

catalog = "app/src/main/assets/runtime/dynamic-host-tool-catalog-v1.json"
replace_exact(catalog, '"catalogVersion": "0.5.14.14"', '"catalogVersion": "0.5.14.15"')

remote = "app/src/main/java/com/luckylca/autocrack/runtime/HostDebuggerRemoteClient.kt"

replace_exact(
    remote,
    '''    fun readRegister(index: Int): GdbRemoteRegisterValue {
        require(index in 0 until MAX_REGISTER_LIMIT) { "Register index out of range" }
        val response = request("p${index.toString(16)}")
        require(!response.startsWith('E')) { "Register read failed: $response" }
        require(response.length % 2 == 0 && response.all(::isHexDigit)) {
            "Unexpected register payload"
        }
        return GdbRemoteRegisterValue(index = index, rawHex = response.lowercase())
    }

    fun readMemory(address: Long, length: Int): GdbRemoteMemoryRead {''',
    '''    fun readRegister(index: Int): GdbRemoteRegisterValue {
        require(index in 0 until MAX_REGISTER_LIMIT) { "Register index out of range" }
        val response = request("p${index.toString(16)}")
        require(!response.startsWith('E')) { "Register read failed: $response" }
        require(response.length % 2 == 0 && response.all(::isHexDigit)) {
            "Unexpected register payload"
        }
        return GdbRemoteRegisterValue(index = index, rawHex = response.lowercase())
    }

    fun queryThreads(maxCount: Int = DEFAULT_THREAD_LIMIT): List<GdbRemoteThreadInfo> {
        require(maxCount in 1..MAX_THREAD_LIMIT) {
            "Thread observation limit must be 1..$MAX_THREAD_LIMIT"
        }
        val ids = LinkedHashSet<String>()
        var response = request("qfThreadInfo")
        while (true) {
            if (response == "l") break
            val batch = GdbRemoteThreadInfoParser.parseThreadBatch(response)
            for (threadId in batch) {
                ids += threadId
                if (ids.size >= maxCount) break
            }
            if (ids.size >= maxCount) break
            response = request("qsThreadInfo")
        }
        return ids.map { threadId ->
            val extra = request("qThreadExtraInfo,$threadId")
            GdbRemoteThreadInfo(
                id = threadId,
                name = GdbRemoteThreadInfoParser.parseExtraInfo(extra),
            )
        }
    }

    fun selectGeneralThread(threadId: String) {
        val normalized = GdbRemoteThreadIdValidator.normalize(threadId)
        val response = request("Hg$normalized")
        require(response == "OK") {
            "LLDB rejected selected general thread $normalized: $response"
        }
    }

    fun readMemory(address: Long, length: Int): GdbRemoteMemoryRead {''',
)

replace_exact(
    remote,
    '''    /** Single-step only the thread identified by the most recent LLDB stop reply. */
    fun step(): String {
        val stopReply = requireNotNull(lastStopReply) {
            "Cannot single-step before LLDB has reported a stopped thread"
        }
        val payload = GdbRemoteExecutionPacketFactory.stepFromStopReply(stopReply)
        val response = requestRunUntilStop(
            payload = payload,
            totalTimeoutMillis = STEP_WAIT_TIMEOUT_MILLIS,
            operationName = payload,
        )
        GdbRemoteRunReplyValidator.requireStopOrExit(payload, response)
        rememberStopReply(response)
        return response
    }
''',
    '''    /** Single-step only the explicitly selected, validated LLDB thread. */
    fun step(threadId: String): String {
        val payload = GdbRemoteExecutionPacketFactory.stepThread(threadId)
        val response = requestRunUntilStop(
            payload = payload,
            totalTimeoutMillis = STEP_WAIT_TIMEOUT_MILLIS,
            operationName = payload,
        )
        GdbRemoteRunReplyValidator.requireStopOrExit(payload, response)
        rememberStopReply(response)
        return response
    }

    /** Backward-compatible stop-thread step; UI/control bridge uses [step] with an explicit TID. */
    fun step(): String {
        val stopReply = requireNotNull(lastStopReply) {
            "Cannot single-step before LLDB has reported a stopped thread"
        }
        return step(GdbRemoteStopReplyParser.requireThreadId(stopReply))
    }
''',
)

replace_exact(
    remote,
    '''data class GdbRemoteRegisterValue(val index: Int, val rawHex: String)

data class GdbRemoteMemoryRead(val address: Long, val bytes: ByteArray) {''',
    '''data class GdbRemoteRegisterValue(val index: Int, val rawHex: String)

data class GdbRemoteThreadInfo(
    val id: String,
    val name: String?,
)

data class GdbRemoteMemoryRead(val address: Long, val bytes: ByteArray) {''',
)

replace_exact(
    remote,
    '''/** Fixed execution-control packet shapes; no caller can provide an arbitrary packet string. */
object GdbRemoteExecutionPacketFactory {
    fun stepFromStopReply(stopReply: String): String =
        "vCont;s:${GdbRemoteStopReplyParser.requireThreadId(stopReply)}"
}

object GdbRemoteRegisterInfoParser {''',
    '''object GdbRemoteThreadIdValidator {
    fun normalize(value: String): String {
        val normalized = value.trim().lowercase()
        require(THREAD_ID_PATTERN.matches(normalized)) { "Invalid GDB remote thread id" }
        val tidPart = normalized.substringAfterLast('.')
        val tid = tidPart.toULongOrNull(16)
        require(tid != null && tid > 0uL) { "GDB remote thread id must be positive" }
        return normalized
    }

    fun matchesTid(value: String, tid: Int): Boolean {
        require(tid > 0) { "TID must be positive" }
        val normalized = runCatching { normalize(value) }.getOrNull() ?: return false
        return normalized.substringAfterLast('.').toULongOrNull(16) == tid.toLong().toULong()
    }

    private val THREAD_ID_PATTERN = Regex("^(?:p[0-9a-f]+\\.)?[0-9a-f]+$")
}

object GdbRemoteThreadInfoParser {
    fun parseThreadBatch(payload: String): List<String> {
        if (payload == "l") return emptyList()
        require(payload.startsWith('m')) { "Unexpected LLDB thread-list response: $payload" }
        val body = payload.drop(1)
        if (body.isBlank()) return emptyList()
        return body.split(',')
            .filter(String::isNotBlank)
            .map(GdbRemoteThreadIdValidator::normalize)
    }

    fun parseExtraInfo(payload: String): String? {
        if (payload.isBlank() || payload.startsWith('E')) return null
        val bytes = runCatching { GdbRemotePacketCodec.decodeHex(payload) }.getOrNull() ?: return null
        return bytes.toString(StandardCharsets.UTF_8)
            .trimEnd('\u0000')
            .take(MAX_THREAD_NAME_CHARS)
            .takeIf(String::isNotBlank)
    }

    private const val MAX_THREAD_NAME_CHARS = 128
}

/** Fixed execution-control packet shapes; no caller can provide an arbitrary packet string. */
object GdbRemoteExecutionPacketFactory {
    fun stepThread(threadId: String): String =
        "vCont;s:${GdbRemoteThreadIdValidator.normalize(threadId)}"

    fun stepFromStopReply(stopReply: String): String =
        stepThread(GdbRemoteStopReplyParser.requireThreadId(stopReply))
}

object GdbRemoteRegisterInfoParser {''',
)

replace_exact(
    remote,
    '''        const val MAX_MEMORY_READ_BYTES = 4096
        const val MAX_REGISTER_LIMIT = 512
        const val DEFAULT_REGISTER_LIMIT = 128
        internal const val ATTACH_WAIT_TIMEOUT_MILLIS = 90_000''',
    '''        const val MAX_MEMORY_READ_BYTES = 4096
        const val MAX_REGISTER_LIMIT = 512
        const val DEFAULT_REGISTER_LIMIT = 128
        const val MAX_THREAD_LIMIT = 256
        const val DEFAULT_THREAD_LIMIT = 64
        internal const val ATTACH_WAIT_TIMEOUT_MILLIS = 90_000''',
)

bridge = "app/src/main/java/com/luckylca/autocrack/runtime/HostDebuggerControlBridge.kt"

replace_exact(
    bridge,
    '''data class HostDebuggerRegisterSnapshot(
    val index: Int,
    val name: String,
    val bitSize: Int?,
    val rawHex: String,
)

data class HostDebuggerControlSnapshot(''',
    '''data class HostDebuggerRegisterSnapshot(
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

data class HostDebuggerControlSnapshot(''',
)

replace_exact(
    bridge,
    '''    val lastStopReply: String?,
    val capabilities: List<String>,
    val registers: List<HostDebuggerRegisterSnapshot>,''',
    '''    val lastStopReply: String?,
    val capabilities: List<String>,
    val threads: List<HostDebuggerThreadSnapshot>,
    val selectedThreadId: String?,
    val lastStepThreadId: String?,
    val stepCompleted: Boolean,
    val threadListCommandSent: Boolean,
    val registers: List<HostDebuggerRegisterSnapshot>,''',
)

replace_exact(
    bridge,
    '''                synchronized(lock) {
                    client = created
                    mutable.connected = true
                    mutable.failure = null
                }
                appendAudit("client_connected_attach_confirmed")
                snapshot()''',
    '''                val threadInfos = runCatching {
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
                val selectedThreadId =
                    threadSnapshots.firstOrNull { it.isMain }?.id
                        ?: stopThreadId?.takeIf { stopped ->
                            threadSnapshots.isEmpty() || threadSnapshots.any { it.id == stopped }
                        }
                        ?: threadSnapshots.firstOrNull()?.id
                if (selectedThreadId != null) {
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
                snapshot()''',
)

replace_exact(
    bridge,
    '''    fun snapshot(): HostDebuggerControlSnapshot = synchronized(lock) { snapshotLocked() }

    suspend fun readRegisters(maxCount: Int = DEFAULT_UI_REGISTER_LIMIT): HostDebuggerControlSnapshot =''',
    '''    fun snapshot(): HostDebuggerControlSnapshot = synchronized(lock) { snapshotLocked() }

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

    suspend fun readRegisters(maxCount: Int = DEFAULT_UI_REGISTER_LIMIT): HostDebuggerControlSnapshot =''',
)

old_step = '''    suspend fun step(): HostDebuggerControlSnapshot = withContext(Dispatchers.IO) {
        requireStoppedClient()
        val activeClient = requireNotNull(client)
        synchronized(lock) {
            mutable.stepCommandSent = true
            mutable.stepAutoInterruptRecovered = false
            mutable.targetRunning = true
            mutable.failure = null
        }
        appendAudit("step_start")
        try {
            val stopReply = activeClient.step()
            synchronized(lock) {
                mutable.lastStopReply = stopReply
                mutable.targetRunning = false
                mutable.failure = null
            }
            appendAudit("step_stop")
            snapshot()
        } catch (timeout: GdbRemoteRunTimeoutException) {
            // PTRACE_SINGLESTEP is allowed to remain inside a blocking syscall. After a short,
            // bounded wait, use the already-authorized fixed gdb-remote interrupt byte to recover
            // a trustworthy stop state instead of forcing the user to wait 30 seconds and press
            // Interrupt manually. This adds no raw packet, signal, write, or breakpoint surface.
            appendAudit("step_wait_timeout_auto_interrupt_start")
            try {
                activeClient.interrupt()
                synchronized(lock) { mutable.interruptCommandSent = true }
                appendAudit("step_auto_interrupt_sent")

                val stopReply = activeClient.awaitStopAfterInterrupt()
                synchronized(lock) {
                    mutable.lastStopReply = stopReply
                    mutable.stepAutoInterruptRecovered = true
                    mutable.targetRunning = false
                    mutable.failure = null
                }
                appendAudit("step_timeout_auto_interrupt_recovered")
                snapshot()
            } catch (recovery: Exception) {
                recovery.addSuppressed(timeout)
                synchronized(lock) {
                    mutable.targetRunning = true
                    mutable.failure =
                        "step 超时且自动 interrupt 恢复失败；目标状态仍未确认：${recovery.message ?: recovery::class.java.simpleName}"
                }
                appendAudit("step_timeout_auto_interrupt_recovery_failed")
                throw recovery
            }
        } catch (exception: Exception) {
            synchronized(lock) {
                mutable.targetRunning = false
                mutable.failure = exception.message ?: exception::class.java.simpleName
            }
            appendAudit("step_failed")
            throw exception
        }
    }
'''
new_step = '''    suspend fun step(): HostDebuggerControlSnapshot = withContext(Dispatchers.IO) {
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
        try {
            val stopReply = activeClient.step(stepThreadId)
            runCatching { activeClient.selectGeneralThread(stepThreadId) }
            synchronized(lock) {
                mutable.lastStopReply = stopReply
                mutable.stepCompleted = true
                mutable.targetRunning = false
                mutable.failure = null
            }
            appendAudit("step_stop")
            snapshot()
        } catch (timeout: GdbRemoteRunTimeoutException) {
            appendAudit("step_wait_timeout_auto_interrupt_start")
            try {
                activeClient.interrupt()
                synchronized(lock) { mutable.interruptCommandSent = true }
                appendAudit("step_auto_interrupt_sent")

                val stopReply = activeClient.awaitStopAfterInterrupt()
                runCatching { activeClient.selectGeneralThread(stepThreadId) }
                synchronized(lock) {
                    mutable.lastStopReply = stopReply
                    mutable.stepCompleted = false
                    mutable.stepAutoInterruptRecovered = true
                    mutable.targetRunning = false
                    mutable.failure = null
                }
                appendAudit("step_timeout_auto_interrupt_recovered")
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
'''
replace_exact(bridge, old_step, new_step)

replace_exact(
    bridge,
    '''                .put("lastStopReply", mutable.lastStopReply ?: JSONObject.NULL)
                .put("continueCommandSent", mutable.continueCommandSent)
                .put("stepCommandSent", mutable.stepCommandSent)
                .put("stepAutoInterruptRecovered", mutable.stepAutoInterruptRecovered)
                .put("interruptCommandSent", mutable.interruptCommandSent)''',
    '''                .put("lastStopReply", mutable.lastStopReply ?: JSONObject.NULL)
                .put("threadListCommandSent", mutable.threadListCommandSent)
                .put("threadCount", mutable.threads.size)
                .put("selectedThreadId", mutable.selectedThreadId ?: JSONObject.NULL)
                .put("lastStepThreadId", mutable.lastStepThreadId ?: JSONObject.NULL)
                .put("stepCompleted", mutable.stepCompleted)
                .put("continueCommandSent", mutable.continueCommandSent)
                .put("stepCommandSent", mutable.stepCommandSent)
                .put("stepAutoInterruptRecovered", mutable.stepAutoInterruptRecovered)
                .put("interruptCommandSent", mutable.interruptCommandSent)''',
)

replace_exact(
    bridge,
    '''        lastStopReply = mutable.lastStopReply,
        capabilities = mutable.capabilities,
        registers = mutable.registers,''',
    '''        lastStopReply = mutable.lastStopReply,
        capabilities = mutable.capabilities,
        threads = mutable.threads,
        selectedThreadId = mutable.selectedThreadId,
        lastStepThreadId = mutable.lastStepThreadId,
        stepCompleted = mutable.stepCompleted,
        threadListCommandSent = mutable.threadListCommandSent,
        registers = mutable.registers,''',
)

replace_exact(
    bridge,
    '''        var lastStopReply: String? = null,
        var capabilities: List<String> = emptyList(),
        var registers: List<HostDebuggerRegisterSnapshot> = emptyList(),''',
    '''        var lastStopReply: String? = null,
        var capabilities: List<String> = emptyList(),
        var threads: List<HostDebuggerThreadSnapshot> = emptyList(),
        var selectedThreadId: String? = null,
        var lastStepThreadId: String? = null,
        var stepCompleted: Boolean = false,
        var threadListCommandSent: Boolean = false,
        var registers: List<HostDebuggerRegisterSnapshot> = emptyList(),''',
)

replace_exact(
    bridge,
    '''        const val DEFAULT_UI_REGISTER_LIMIT = 32
        const val MAX_UI_REGISTER_LIMIT = 128
        const val MAX_UI_MEMORY_READ_BYTES = 512''',
    '''        const val DEFAULT_UI_REGISTER_LIMIT = 32
        const val MAX_UI_REGISTER_LIMIT = 128
        const val DEFAULT_UI_THREAD_LIMIT = 64
        const val MAX_UI_THREAD_LIMIT = 128
        const val MAX_UI_MEMORY_READ_BYTES = 512''',
)

ui = "app/src/main/java/com/luckylca/autocrack/ui/DebuggerSessionScreen.kt"

replace_exact(
    ui,
    '''    fun readMemory() {
        val address = parseHexAddress(memoryAddressText)
        val length = memoryLengthText.toIntOrNull()
        if (address == null || length == null) {
            status = "请输入有效十六进制地址和读取长度"
            return
        }
        scope.launch {
            loading = true
            status = "正在执行限长内存读取；不会写入目标内存"
            runCatching { controlBridge.readMemory(address, length) }
                .onSuccess { result ->
                    controlSnapshot = result
                    status = "内存读取完成：0x${address.toString(16)}，${result.lastMemoryHex?.length?.div(2) ?: 0} B"
                }
                .onFailure { exception -> status = exception.message ?: "内存读取失败" }
            loading = false
        }
    }

    fun stepTarget() {''',
    '''    fun readMemory() {
        val address = parseHexAddress(memoryAddressText)
        val length = memoryLengthText.toIntOrNull()
        if (address == null || length == null) {
            status = "请输入有效十六进制地址和读取长度"
            return
        }
        scope.launch {
            loading = true
            status = "正在执行限长内存读取；不会写入目标内存"
            runCatching { controlBridge.readMemory(address, length) }
                .onSuccess { result ->
                    controlSnapshot = result
                    status = "内存读取完成：0x${address.toString(16)}，${result.lastMemoryHex?.length?.div(2) ?: 0} B"
                }
                .onFailure { exception -> status = exception.message ?: "内存读取失败" }
            loading = false
        }
    }

    fun refreshThreads() {
        scope.launch {
            loading = true
            status = "正在通过 typed qfThreadInfo/qThreadExtraInfo 只读枚举线程"
            runCatching { controlBridge.refreshThreads() }
                .onSuccess { result ->
                    controlSnapshot = result
                    status =
                        "线程枚举完成：${result.threads.size} 个；当前选择=${result.selectedThreadId ?: "无"}"
                }
                .onFailure { exception -> status = exception.message ?: "线程枚举失败" }
            loading = false
        }
    }

    fun selectThread(threadId: String) {
        scope.launch {
            loading = true
            status = "正在选择线程 $threadId 作为寄存器读取/单步目标"
            runCatching { controlBridge.selectThread(threadId) }
                .onSuccess { result ->
                    controlSnapshot = result
                    status = "已选择线程 ${result.selectedThreadId ?: "无"}"
                }
                .onFailure { exception -> status = exception.message ?: "线程选择失败" }
            loading = false
        }
    }

    fun stepTarget() {''',
)

replace_exact(
    ui,
    '''            status = "正在单步执行 1 条指令；2 秒内无 stop reply 时将自动 protocol interrupt 恢复"
            runCatching { controlBridge.step() }
                .onSuccess { result ->
                    controlSnapshot = result
                    status = if (result.stepAutoInterruptRecovered) {
                        "单步未在 2 秒窗口内返回，已自动 interrupt 恢复可信 stop：${result.lastStopReply ?: "未知"}"
                    } else {
                        "单步完成：stop=${result.lastStopReply ?: "未知"}"
                    }
                }''',
    '''            val selected = controlSnapshot.selectedThreadId
            status = "正在对所选线程 ${selected ?: "未选择"} 单步 1 条指令；阻塞 syscall 超时会自动 interrupt 恢复"
            runCatching { controlBridge.step() }
                .onSuccess { result ->
                    controlSnapshot = result
                    status = when {
                        result.stepCompleted ->
                            "单步完成：thread=${result.lastStepThreadId ?: "未知"} stop=${result.lastStopReply ?: "未知"}"
                        result.stepAutoInterruptRecovered ->
                            "线程 ${result.lastStepThreadId ?: "未知"} 在 2 秒内没有完成一条指令（常见于阻塞 syscall）；已自动 interrupt 恢复停止状态。注意：恢复成功，但这次 step 没有完成。"
                        else -> "step 未完成：stop=${result.lastStopReply ?: "未知"}"
                    }
                }''',
)

replace_exact(
    ui,
    '''        item {
            DebuggerCard("4. 只读观察") {
                OutlinedTextField(
                    value = registerLimitText,''',
    '''        item {
            DebuggerCard("4. 只读观察") {
                Text(
                    "线程是调试上下文的一部分。默认优先选择目标进程主线程；如果它正阻塞在 Looper/syscall，可手动选择其他线程再 STEP。",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = ::refreshThreads,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !loading && controlSnapshot.connected && !controlSnapshot.targetRunning,
                ) { Text("刷新线程列表（只读）") }
                Text(
                    "selectedThread=${controlSnapshot.selectedThreadId ?: "无"} threadCount=${controlSnapshot.threads.size}",
                    fontFamily = FontFamily.Monospace,
                )
                controlSnapshot.threads.take(MAX_THREAD_ROWS).forEach { thread ->
                    OutlinedButton(
                        onClick = { selectThread(thread.id) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !loading && !controlSnapshot.targetRunning &&
                            controlSnapshot.selectedThreadId != thread.id,
                    ) {
                        Text(
                            buildString {
                                if (controlSnapshot.selectedThreadId == thread.id) append("▶ ")
                                if (thread.isMain) append("[main] ")
                                append("TID=${thread.id} ")
                                append(thread.name ?: "<unnamed>")
                            },
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                OutlinedTextField(
                    value = registerLimitText,''',
)

replace_exact(
    ui,
    '''                    enabled = !loading && controlSnapshot.connected && !controlSnapshot.targetRunning,
                ) { Text("Single step 1 instruction") }''',
    '''                    enabled = !loading && controlSnapshot.connected && !controlSnapshot.targetRunning &&
                        controlSnapshot.selectedThreadId != null,
                ) { Text("Single step selected thread") }''',
)

replace_exact(
    ui,
    '''        "continue=${snapshot.continueCommandSent} step=${snapshot.stepCommandSent} stepAutoInterruptRecovered=${snapshot.stepAutoInterruptRecovered} interrupt=${snapshot.interruptCommandSent}",''',
    '''        "continue=${snapshot.continueCommandSent} step=${snapshot.stepCommandSent} stepCompleted=${snapshot.stepCompleted} stepAutoInterruptRecovered=${snapshot.stepAutoInterruptRecovered} interrupt=${snapshot.interruptCommandSent}",''',
)

replace_exact(
    ui,
    '''    Text("lastStop=${snapshot.lastStopReply ?: "无"}", fontFamily = FontFamily.Monospace)
    Text(
        "continue=${snapshot.continueCommandSent}''',
    '''    Text("lastStop=${snapshot.lastStopReply ?: "无"}", fontFamily = FontFamily.Monospace)
    Text(
        "threads=${snapshot.threads.size} selected=${snapshot.selectedThreadId ?: "无"} lastStepThread=${snapshot.lastStepThreadId ?: "无"}",
        fontFamily = FontFamily.Monospace,
    )
    Text(
        "continue=${snapshot.continueCommandSent}''',
)

replace_exact(
    ui,
    '''    appendLine("capabilityCount=${control.capabilities.size}")
    appendLine("continueCommandSent=${control.continueCommandSent}")
    appendLine("stepCommandSent=${control.stepCommandSent}")
    appendLine("stepAutoInterruptRecovered=${control.stepAutoInterruptRecovered}")''',
    '''    appendLine("capabilityCount=${control.capabilities.size}")
    appendLine("threadListCommandSent=${control.threadListCommandSent} threadCount=${control.threads.size}")
    appendLine("selectedThreadId=${control.selectedThreadId ?: "无"}")
    appendLine("lastStepThreadId=${control.lastStepThreadId ?: "无"}")
    appendLine("continueCommandSent=${control.continueCommandSent}")
    appendLine("stepCommandSent=${control.stepCommandSent}")
    appendLine("stepCompleted=${control.stepCompleted}")
    appendLine("stepAutoInterruptRecovered=${control.stepAutoInterruptRecovered}")''',
)

replace_exact(
    ui,
    '''private const val MAX_PROCESS_ROWS = 24
private const val MAX_REGISTER_ROWS = 48''',
    '''private const val MAX_PROCESS_ROWS = 24
private const val MAX_THREAD_ROWS = 32
private const val MAX_REGISTER_ROWS = 48''',
)

remote_test = "app/src/test/java/com/luckylca/autocrack/runtime/HostDebuggerRemoteClientTest.kt"

replace_exact(
    remote_test,
    '''    @Test
    fun runReplyValidatorAcceptsOnlyStopOrExitPackets() {''',
    '''    @Test
    fun typedThreadIdsAreValidatedBeforePacketConstruction() {
        assertEquals("1b24", GdbRemoteThreadIdValidator.normalize("1B24"))
        assertEquals("p123.456", GdbRemoteThreadIdValidator.normalize("p123.456"))
        assertEquals("vCont;s:1b24", GdbRemoteExecutionPacketFactory.stepThread("1B24"))
        assertTrue(GdbRemoteThreadIdValidator.matchesTid("1b24", 6948))
        assertThrows(IllegalArgumentException::class.java) {
            GdbRemoteExecutionPacketFactory.stepThread("1b24;vCont;c")
        }
    }

    @Test
    fun parsesBoundedThreadListAndNames() {
        assertEquals(
            listOf("1b24", "p123.456"),
            GdbRemoteThreadInfoParser.parseThreadBatch("m1b24,p123.456"),
        )
        assertTrue(GdbRemoteThreadInfoParser.parseThreadBatch("l").isEmpty())
        assertEquals(
            "Signal Catcher",
            GdbRemoteThreadInfoParser.parseExtraInfo("5369676e616c2043617463686572"),
        )
        assertNull(GdbRemoteThreadInfoParser.parseExtraInfo("E01"))
    }

    @Test
    fun runReplyValidatorAcceptsOnlyStopOrExitPackets() {''',
)

print("Applied AutoCrackApp phase 5.14.15 thread-aware step patch")
