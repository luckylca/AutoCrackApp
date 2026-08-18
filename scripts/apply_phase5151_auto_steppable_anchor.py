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
replace_exact(gradle, "versionCode = 51", "versionCode = 52")
replace_exact(
    gradle,
    'versionName = "0.5.15.0-phase5.15-typed-hw-breakpoints"',
    'versionName = "0.5.15.1-phase5.15-auto-steppable-anchor"',
)

catalog = "app/src/main/assets/runtime/dynamic-host-tool-catalog-v1.json"
replace_exact(catalog, '"catalogVersion": "0.5.15.0"', '"catalogVersion": "0.5.15.1"')
replace_exact(
    catalog,
    '''        {"id": "hardware_breakpoint_remove", "changesTargetState": true, "packetShape": "z1,<trackedAddress>,4"},
        {"id": "continue", "changesTargetState": true},''',
    '''        {"id": "hardware_breakpoint_remove", "changesTargetState": true, "packetShape": "z1,<trackedAddress>,4"},
        {"id": "auto_steppable_anchor", "changesTargetState": true, "strategy": "prefer-main-thread-current-pc-one-shot-hardware-breakpoint"},
        {"id": "continue", "changesTargetState": true},''',
)
replace_exact(
    catalog,
    '        "software breakpoint Z0/z0 packets are not exposed",',
    '        "software breakpoint Z0/z0 packets are not exposed",\n'
    '        "automatic stepping anchor prefers the process main TID, reads only its PC, inserts one typed Z1 hardware execution breakpoint at that validated PC, then removes the one-shot anchor after the next continue stop",\n'
    '        "the thread from each continue stop reply is automatically selected as the next register/step context",',
)

remote = "app/src/main/java/com/luckylca/autocrack/runtime/HostDebuggerRemoteClient.kt"
replace_exact(
    remote,
    '''/** Fixed execution-control packet shapes; no caller can provide an arbitrary packet string. */
object GdbRemoteExecutionPacketFactory {''',
    '''object GdbRemoteRegisterValueDecoder {
    fun unsignedLittleEndianLong(rawHex: String): Long {
        val bytes = GdbRemotePacketCodec.decodeHex(rawHex)
        require(bytes.isNotEmpty() && bytes.size <= Long.SIZE_BYTES) {
            "Register value must contain 1..8 bytes"
        }
        var value = 0L
        bytes.forEachIndexed { index, byte ->
            value = value or ((byte.toLong() and 0xffL) shl (index * 8))
        }
        require(value > 0L) { "Register value is not a positive user-space address" }
        return value
    }
}

/** Fixed execution-control packet shapes; no caller can provide an arbitrary packet string. */
object GdbRemoteExecutionPacketFactory {''',
)

bridge = "app/src/main/java/com/luckylca/autocrack/runtime/HostDebuggerControlBridge.kt"

# Prefer the real process leader directly instead of depending on where it appears in a bounded
# qfThreadInfo result. This fixes large Android processes whose main TID is outside the first 64.
replace_exact(
    bridge,
    '''                val stopThreadId = GdbRemoteStopReplyParser.threadId(attachReply)
                val selectedThreadId =
                    threadSnapshots.firstOrNull { it.isMain }?.id
                        ?: stopThreadId?.takeIf { stopped ->
                            threadSnapshots.isEmpty() || threadSnapshots.any { it.id == stopped }
                        }
                        ?: threadSnapshots.firstOrNull()?.id
                if (selectedThreadId != null) {
                    runCatching { created.selectGeneralThread(selectedThreadId) }
                }
''',
    '''                val stopThreadId = GdbRemoteStopReplyParser.threadId(attachReply)
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
''',
)

replace_exact(
    bridge,
    '''    val breakpointSetCommandSent: Boolean,
    val breakpointRemoveCommandSent: Boolean,
    val registers: List<HostDebuggerRegisterSnapshot>,''',
    '''    val breakpointSetCommandSent: Boolean,
    val breakpointRemoveCommandSent: Boolean,
    val autoAnchorPrepared: Boolean,
    val autoAnchorThreadId: String?,
    val autoAnchorAddress: Long?,
    val autoAnchorStopObserved: Boolean,
    val registers: List<HostDebuggerRegisterSnapshot>,''',
)

replace_exact(
    bridge,
    '''    suspend fun refreshThreads(maxCount: Int = DEFAULT_UI_THREAD_LIMIT): HostDebuggerControlSnapshot =''',
    '''    /**
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
            snapshot()
        }

    suspend fun refreshThreads(maxCount: Int = DEFAULT_UI_THREAD_LIMIT): HostDebuggerControlSnapshot =''',
)

# After every Continue stop, automatically select the actual stopped thread. If this Continue used
# the one-shot auto anchor, clean that anchor up before exposing the stopped state for stepping.
replace_exact(
    bridge,
    '''                .onSuccess { stopReply ->
                    synchronized(lock) {
                        mutable.lastStopReply = stopReply
                        mutable.targetRunning = false
                        mutable.failure = null
                    }
                    appendAudit("continue_stop")
                }''',
    '''                .onSuccess { stopReply ->
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

                    synchronized(lock) {
                        mutable.lastStopReply = stopReply
                        if (stoppedThreadId != null) mutable.selectedThreadId = stoppedThreadId
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
                }''',
)

replace_exact(
    bridge,
    '''                .put("breakpointCount", mutable.breakpoints.size)
                .put("breakpointMode", "hardware_execution_only")
                .put("rawPacketAdapterExposed", false)''',
    '''                .put("breakpointCount", mutable.breakpoints.size)
                .put("breakpointMode", "hardware_execution_only")
                .put("autoAnchorPrepared", mutable.autoAnchorPrepared)
                .put("autoAnchorThreadId", mutable.autoAnchorThreadId ?: JSONObject.NULL)
                .put("autoAnchorAddress", mutable.autoAnchorAddress ?: JSONObject.NULL)
                .put("autoAnchorStopObserved", mutable.autoAnchorStopObserved)
                .put("rawPacketAdapterExposed", false)''',
)
replace_exact(
    bridge,
    '''        breakpointSetCommandSent = mutable.breakpointSetCommandSent,
        breakpointRemoveCommandSent = mutable.breakpointRemoveCommandSent,
        registers = mutable.registers,''',
    '''        breakpointSetCommandSent = mutable.breakpointSetCommandSent,
        breakpointRemoveCommandSent = mutable.breakpointRemoveCommandSent,
        autoAnchorPrepared = mutable.autoAnchorPrepared,
        autoAnchorThreadId = mutable.autoAnchorThreadId,
        autoAnchorAddress = mutable.autoAnchorAddress,
        autoAnchorStopObserved = mutable.autoAnchorStopObserved,
        registers = mutable.registers,''',
)
replace_exact(
    bridge,
    '''        var breakpointSetCommandSent: Boolean = false,
        var breakpointRemoveCommandSent: Boolean = false,
        var registers: List<HostDebuggerRegisterSnapshot> = emptyList(),''',
    '''        var breakpointSetCommandSent: Boolean = false,
        var breakpointRemoveCommandSent: Boolean = false,
        var autoAnchorPrepared: Boolean = false,
        var autoAnchorThreadId: String? = null,
        var autoAnchorAddress: Long? = null,
        var autoAnchorOwnsBreakpoint: Boolean = false,
        var autoAnchorStopObserved: Boolean = false,
        var registers: List<HostDebuggerRegisterSnapshot> = emptyList(),''',
)
replace_exact(
    bridge,
    '''        const val DEFAULT_UI_THREAD_LIMIT = 64
        const val MAX_UI_THREAD_LIMIT = 128
        const val MAX_UI_HARDWARE_BREAKPOINTS = 8''',
    '''        const val DEFAULT_UI_THREAD_LIMIT = 128
        const val MAX_UI_THREAD_LIMIT = 256
        const val MAX_UI_HARDWARE_BREAKPOINTS = 8
        const val AUTO_REGISTER_METADATA_LIMIT = 64
        const val AARCH64_PC_REGISTER_FALLBACK_INDEX = 32''',
)

ui = "app/src/main/java/com/luckylca/autocrack/ui/DebuggerSessionScreen.kt"

replace_exact(
    ui,
    '''    fun setHardwareBreakpoint() {''',
    '''    fun autoRunToSteppablePosition() {
        scope.launch {
            loading = true
            status = "正在自动选择主线程并读取 PC；随后设置一次性硬件执行锚点"
            runCatching {
                val prepared = controlBridge.autoPrepareSteppableAnchor()
                controlSnapshot = prepared
                val running = controlBridge.continueTarget()
                controlSnapshot = running
                running
            }.onSuccess { result ->
                status =
                    "已自动选择 thread=${result.autoAnchorThreadId ?: "未知"}，PC=${result.autoAnchorAddress?.let { "0x${it.toString(16)}" } ?: "未知"} 并 Continue。现在直接操作目标 App；从阻塞 syscall 返回或再次执行该 PC 时会自动停下并切换到命中线程。"
            }.onFailure { exception ->
                controlSnapshot = controlBridge.snapshot()
                status = exception.message ?: "自动准备可单步位置失败"
            }
            loading = false
        }
    }

    fun setHardwareBreakpoint() {''',
)

replace_exact(
    ui,
    '''        item {
            DebuggerCard("6. 执行控制") {
                Text("continue/step 会改变目标执行状态；硬件执行断点只会由上面的显式 typed 操作设置。")''',
    '''        item {
            DebuggerCard("6. 自动进入可单步位置 / 执行控制") {
                Text(
                    "推荐直接使用自动模式：AutoCrack 优先选择主线程，读取其当前 PC，设置一次性 Z1 硬件执行锚点并 Continue；下一次 stop 会自动选择真正停止的线程并清理该锚点。无需手工填写地址或挑线程。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = ::autoRunToSteppablePosition,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !loading && controlSnapshot.connected && !controlSnapshot.targetRunning,
                ) { Text("自动选择线程 + 地址并运行") }
                Text(
                    "autoPrepared=${controlSnapshot.autoAnchorPrepared} autoThread=${controlSnapshot.autoAnchorThreadId ?: "无"} autoPC=${controlSnapshot.autoAnchorAddress?.let { "0x${it.toString(16)}" } ?: "无"} stopObserved=${controlSnapshot.autoAnchorStopObserved}",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("continue/step 会改变目标执行状态；手工硬件断点仍可在上面的高级区域使用。")''',
)

replace_exact(
    ui,
    '''    appendLine("breakpointCount=${control.breakpoints.size}")
    appendLine("rawPacketAdapterExposed=false")''',
    '''    appendLine("breakpointCount=${control.breakpoints.size}")
    appendLine("autoAnchorPrepared=${control.autoAnchorPrepared}")
    appendLine("autoAnchorThreadId=${control.autoAnchorThreadId ?: "无"}")
    appendLine("autoAnchorAddress=${control.autoAnchorAddress?.let { "0x${it.toString(16)}" } ?: "无"}")
    appendLine("autoAnchorStopObserved=${control.autoAnchorStopObserved}")
    appendLine("rawPacketAdapterExposed=false")''',
)

replace_exact(
    ui,
    '''    appendLine("边界：loopback only；寄存器/内存只读；允许显式授权 typed attach / continue / step / interrupt / AArch64 hardware execution breakpoint；无 software breakpoint / register write / memory write / raw packet adapter")''',
    '''    appendLine("边界：loopback only；寄存器/内存只读；允许显式授权 typed attach / continue / step / interrupt / AArch64 hardware execution breakpoint；支持自动 main-thread PC one-shot anchor；无 software breakpoint / register write / memory write / raw packet adapter")''',
)

remote_test = "app/src/test/java/com/luckylca/autocrack/runtime/HostDebuggerRemoteClientTest.kt"
replace_exact(
    remote_test,
    '''    @Test
    fun runReplyValidatorAcceptsOnlyStopOrExitPackets() {''',
    '''    @Test
    fun decodesAarch64LittleEndianProgramCounter() {
        assertEquals(
            0x0000007daa219a8cL,
            GdbRemoteRegisterValueDecoder.unsignedLittleEndianLong("8c9a21aa7d000000"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            GdbRemoteRegisterValueDecoder.unsignedLittleEndianLong("0000000000000000")
        }
    }

    @Test
    fun runReplyValidatorAcceptsOnlyStopOrExitPackets() {''',
)

print("Applied AutoCrackApp phase 5.15.1 automatic steppable-anchor patch")
