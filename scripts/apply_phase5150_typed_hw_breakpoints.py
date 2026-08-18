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
replace_exact(gradle, "versionCode = 50", "versionCode = 51")
replace_exact(
    gradle,
    'versionName = "0.5.14.15-phase5.14-thread-aware-step"',
    'versionName = "0.5.15.0-phase5.15-typed-hw-breakpoints"',
)

catalog = "app/src/main/assets/runtime/dynamic-host-tool-catalog-v1.json"
replace_exact(catalog, '"catalogVersion": "0.5.14.15"', '"catalogVersion": "0.5.15.0"')
replace_exact(
    catalog,
    '    "breakpointAllowed": false,',
    '    "breakpointAllowed": true,\n'
    '    "breakpointKinds": ["hardware_execution"],\n'
    '    "breakpointRequiresStoppedTarget": true,',
)
replace_exact(
    catalog,
    '''        {"id": "memory_read", "changesTargetState": false, "maxBytes": 512},
        {"id": "continue", "changesTargetState": true},''',
    '''        {"id": "memory_read", "changesTargetState": false, "maxBytes": 512},
        {"id": "hardware_breakpoint_set", "changesTargetState": true, "packetShape": "Z1,<validatedAddress>,4", "maxTracked": 8},
        {"id": "hardware_breakpoint_remove", "changesTargetState": true, "packetShape": "z1,<trackedAddress>,4"},
        {"id": "continue", "changesTargetState": true},''',
)
replace_exact(
    catalog,
    '        "no breakpoint insertion or removal adapter",',
    '        "breakpoints are limited to typed AArch64 hardware execution Z1/z1 packets at positive 4-byte-aligned addresses",\n'
    '        "software breakpoint Z0/z0 packets are not exposed",',
)

remote = "app/src/main/java/com/luckylca/autocrack/runtime/HostDebuggerRemoteClient.kt"
replace_exact(
    remote,
    '''    fun selectGeneralThread(threadId: String) {
        val normalized = GdbRemoteThreadIdValidator.normalize(threadId)
        val response = request("Hg$normalized")
        require(response == "OK") {
            "LLDB rejected selected general thread $normalized: $response"
        }
    }

    fun readMemory(address: Long, length: Int): GdbRemoteMemoryRead {''',
    '''    fun selectGeneralThread(threadId: String) {
        val normalized = GdbRemoteThreadIdValidator.normalize(threadId)
        val response = request("Hg$normalized")
        require(response == "OK") {
            "LLDB rejected selected general thread $normalized: $response"
        }
    }

    /** Insert only a typed AArch64 hardware execution breakpoint (gdb-remote Z1). */
    fun setHardwareExecutionBreakpoint(address: Long): GdbRemoteBreakpoint {
        val normalized = GdbRemoteHardwareBreakpointValidator.normalizeAddress(address)
        val payload = GdbRemoteBreakpointPacketFactory.insertHardwareExecution(normalized)
        val response = request(payload)
        require(response == "OK") {
            "LLDB rejected hardware execution breakpoint at 0x${normalized.toString(16)}: $response"
        }
        return GdbRemoteBreakpoint(
            address = normalized,
            kindBytes = GdbRemoteHardwareBreakpointValidator.AARCH64_INSTRUCTION_BYTES,
        )
    }

    /** Remove only a typed AArch64 hardware execution breakpoint previously tracked by the bridge. */
    fun removeHardwareExecutionBreakpoint(address: Long) {
        val normalized = GdbRemoteHardwareBreakpointValidator.normalizeAddress(address)
        val payload = GdbRemoteBreakpointPacketFactory.removeHardwareExecution(normalized)
        val response = request(payload)
        require(response == "OK") {
            "LLDB rejected hardware breakpoint removal at 0x${normalized.toString(16)}: $response"
        }
    }

    fun readMemory(address: Long, length: Int): GdbRemoteMemoryRead {''',
)
replace_exact(
    remote,
    '''data class GdbRemoteThreadInfo(
    val id: String,
    val name: String?,
)

data class GdbRemoteMemoryRead(val address: Long, val bytes: ByteArray) {''',
    '''data class GdbRemoteThreadInfo(
    val id: String,
    val name: String?,
)

data class GdbRemoteBreakpoint(
    val address: Long,
    val kindBytes: Int,
)

data class GdbRemoteMemoryRead(val address: Long, val bytes: ByteArray) {''',
)
replace_exact(
    remote,
    '''/** Fixed execution-control packet shapes; no caller can provide an arbitrary packet string. */
object GdbRemoteExecutionPacketFactory {''',
    '''object GdbRemoteHardwareBreakpointValidator {
    const val AARCH64_INSTRUCTION_BYTES = 4

    fun normalizeAddress(address: Long): Long {
        require(address > 0L) { "Hardware breakpoint address must be positive" }
        require(address % AARCH64_INSTRUCTION_BYTES == 0L) {
            "AArch64 execution breakpoint address must be 4-byte aligned"
        }
        return address
    }
}

/** Fixed Z1/z1 packet shapes; callers cannot select software/watchpoint/raw packet kinds. */
object GdbRemoteBreakpointPacketFactory {
    fun insertHardwareExecution(address: Long): String {
        val normalized = GdbRemoteHardwareBreakpointValidator.normalizeAddress(address)
        return "Z1,${normalized.toString(16)},${GdbRemoteHardwareBreakpointValidator.AARCH64_INSTRUCTION_BYTES}"
    }

    fun removeHardwareExecution(address: Long): String {
        val normalized = GdbRemoteHardwareBreakpointValidator.normalizeAddress(address)
        return "z1,${normalized.toString(16)},${GdbRemoteHardwareBreakpointValidator.AARCH64_INSTRUCTION_BYTES}"
    }
}

/** Fixed execution-control packet shapes; no caller can provide an arbitrary packet string. */
object GdbRemoteExecutionPacketFactory {''',
)

bridge = "app/src/main/java/com/luckylca/autocrack/runtime/HostDebuggerControlBridge.kt"
replace_exact(
    bridge,
    '''data class HostDebuggerThreadSnapshot(
    val id: String,
    val name: String?,
    val isMain: Boolean,
)

data class HostDebuggerControlSnapshot(''',
    '''data class HostDebuggerThreadSnapshot(
    val id: String,
    val name: String?,
    val isMain: Boolean,
)

data class HostDebuggerBreakpointSnapshot(
    val address: Long,
    val kindBytes: Int,
)

data class HostDebuggerControlSnapshot(''',
)
replace_exact(
    bridge,
    '''    val stepCompleted: Boolean,
    val threadListCommandSent: Boolean,
    val registers: List<HostDebuggerRegisterSnapshot>,''',
    '''    val stepCompleted: Boolean,
    val threadListCommandSent: Boolean,
    val breakpoints: List<HostDebuggerBreakpointSnapshot>,
    val breakpointSetCommandSent: Boolean,
    val breakpointRemoveCommandSent: Boolean,
    val registers: List<HostDebuggerRegisterSnapshot>,''',
)
replace_exact(
    bridge,
    '''    fun snapshot(): HostDebuggerControlSnapshot = synchronized(lock) { snapshotLocked() }

    suspend fun refreshThreads(maxCount: Int = DEFAULT_UI_THREAD_LIMIT): HostDebuggerControlSnapshot =''',
    '''    fun snapshot(): HostDebuggerControlSnapshot = synchronized(lock) { snapshotLocked() }

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

    suspend fun refreshThreads(maxCount: Int = DEFAULT_UI_THREAD_LIMIT): HostDebuggerControlSnapshot =''',
)
replace_exact(
    bridge,
    '''                .put("registerWriteCommandSent", false)
                .put("memoryWriteCommandSent", false)
                .put("breakpointCommandSent", false)
                .put("rawPacketAdapterExposed", false)''',
    '''                .put("registerWriteCommandSent", false)
                .put("memoryWriteCommandSent", false)
                .put("breakpointCommandSent", mutable.breakpointCommandSent)
                .put("breakpointSetCommandSent", mutable.breakpointSetCommandSent)
                .put("breakpointRemoveCommandSent", mutable.breakpointRemoveCommandSent)
                .put("breakpointCount", mutable.breakpoints.size)
                .put("breakpointMode", "hardware_execution_only")
                .put("rawPacketAdapterExposed", false)''',
)
replace_exact(
    bridge,
    '''        stepCompleted = mutable.stepCompleted,
        threadListCommandSent = mutable.threadListCommandSent,
        registers = mutable.registers,''',
    '''        stepCompleted = mutable.stepCompleted,
        threadListCommandSent = mutable.threadListCommandSent,
        breakpoints = mutable.breakpoints,
        breakpointSetCommandSent = mutable.breakpointSetCommandSent,
        breakpointRemoveCommandSent = mutable.breakpointRemoveCommandSent,
        registers = mutable.registers,''',
)
replace_exact(
    bridge,
    '''        registerWriteCommandSent = false,
        memoryWriteCommandSent = false,
        breakpointCommandSent = false,
        failure = mutable.failure,''',
    '''        registerWriteCommandSent = false,
        memoryWriteCommandSent = false,
        breakpointCommandSent = mutable.breakpointCommandSent,
        failure = mutable.failure,''',
)
replace_exact(
    bridge,
    '''        var stepCompleted: Boolean = false,
        var threadListCommandSent: Boolean = false,
        var registers: List<HostDebuggerRegisterSnapshot> = emptyList(),''',
    '''        var stepCompleted: Boolean = false,
        var threadListCommandSent: Boolean = false,
        var breakpoints: List<HostDebuggerBreakpointSnapshot> = emptyList(),
        var breakpointCommandSent: Boolean = false,
        var breakpointSetCommandSent: Boolean = false,
        var breakpointRemoveCommandSent: Boolean = false,
        var registers: List<HostDebuggerRegisterSnapshot> = emptyList(),''',
)
replace_exact(
    bridge,
    '''        const val DEFAULT_UI_THREAD_LIMIT = 64
        const val MAX_UI_THREAD_LIMIT = 128
        const val MAX_UI_MEMORY_READ_BYTES = 512''',
    '''        const val DEFAULT_UI_THREAD_LIMIT = 64
        const val MAX_UI_THREAD_LIMIT = 128
        const val MAX_UI_HARDWARE_BREAKPOINTS = 8
        const val MAX_UI_MEMORY_READ_BYTES = 512''',
)
replace_exact(
    bridge,
    '''        val shouldInterrupt = synchronized(lock) { mutable.connected && mutable.targetRunning }
        if (shouldInterrupt) runCatching { interrupt() }
        synchronized(lock) {
            client?.close()
            client = null
            mutable.connected = false
            mutable.targetRunning = false
        }''',
    '''        val shouldInterrupt = synchronized(lock) { mutable.connected && mutable.targetRunning }
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
        }''',
)

ui = "app/src/main/java/com/luckylca/autocrack/ui/DebuggerSessionScreen.kt"
replace_exact(
    ui,
    '''    var memoryAddressText by remember { mutableStateOf("") }
    var memoryLengthText by remember { mutableStateOf("32") }
    var processReport''',
    '''    var memoryAddressText by remember { mutableStateOf("") }
    var memoryLengthText by remember { mutableStateOf("32") }
    var breakpointAddressText by remember { mutableStateOf("") }
    var processReport''',
)
replace_exact(
    ui,
    '''            status = "正在连接 127.0.0.1 targetless LLDB server，复核目标后发送固定 typed vAttach；不会发送写入或断点命令"''',
    '''            status = "正在连接 127.0.0.1 targetless LLDB server，复核目标后发送固定 typed vAttach；连接阶段不会发送断点、写入或 raw packet"''',
)
replace_exact(
    ui,
    '''    fun refreshThreads() {''',
    '''    fun setHardwareBreakpoint() {
        val address = parseHexAddress(breakpointAddressText)
        if (address == null) {
            status = "请输入有效的 AArch64 指令地址（hex）"
            return
        }
        scope.launch {
            loading = true
            status = "正在设置 typed AArch64 硬件执行断点：0x${address.toString(16)}"
            runCatching { controlBridge.setHardwareExecutionBreakpoint(address) }
                .onSuccess { result ->
                    controlSnapshot = result
                    status = "硬件执行断点已设置：0x${address.toString(16)}；现在可以 Continue 等待命中"
                }
                .onFailure { exception -> status = exception.message ?: "硬件执行断点设置失败" }
            loading = false
        }
    }

    fun removeHardwareBreakpoint(address: Long) {
        scope.launch {
            loading = true
            status = "正在移除当前会话跟踪的硬件执行断点：0x${address.toString(16)}"
            runCatching { controlBridge.removeHardwareExecutionBreakpoint(address) }
                .onSuccess { result ->
                    controlSnapshot = result
                    status = "硬件执行断点已移除：0x${address.toString(16)}"
                }
                .onFailure { exception -> status = exception.message ?: "硬件执行断点移除失败" }
            loading = false
        }
    }

    fun refreshThreads() {''',
)
replace_exact(
    ui,
    '''                "边界：可读取寄存器/限长内存并执行 continue/step/interrupt；没有寄存器写、内存写、断点或任意 raw packet 接口。",''',
    '''                "边界：寄存器/内存只读；允许 typed AArch64 硬件执行断点与 continue/step/interrupt；无软件断点、寄存器写、内存写或任意 raw packet。",''',
)
replace_exact(
    ui,
    '''        item {
            DebuggerCard("5. 执行控制") {
                Text("continue/step 会改变目标执行状态，但不会修改寄存器值、内存内容或插入断点。")''',
    '''        item {
            DebuggerCard("5. 受控硬件执行断点") {
                Text(
                    "仅支持 AArch64 hardware execution breakpoint：固定 Z1/z1、4 字节对齐、单会话最多跟踪 8 个；不使用会改写代码页的 Z0 软件断点。",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = breakpointAddressText,
                    onValueChange = { breakpointAddressText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("执行断点地址（hex，例如 0x7abc...）") },
                    singleLine = true,
                )
                Button(
                    onClick = ::setHardwareBreakpoint,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !loading && controlSnapshot.connected && !controlSnapshot.targetRunning,
                ) { Text("设置 typed 硬件执行断点") }
                Text(
                    "trackedBreakpoints=${controlSnapshot.breakpoints.size}",
                    fontFamily = FontFamily.Monospace,
                )
                controlSnapshot.breakpoints.forEach { breakpoint ->
                    OutlinedButton(
                        onClick = { removeHardwareBreakpoint(breakpoint.address) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !loading && !controlSnapshot.targetRunning,
                    ) {
                        Text(
                            "移除 HW 0x${breakpoint.address.toString(16)} kind=${breakpoint.kindBytes}",
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
        item {
            DebuggerCard("6. 执行控制") {
                Text("continue/step 会改变目标执行状态；硬件执行断点只会由上面的显式 typed 操作设置。")''',
)
replace_exact(ui, 'DebuggerCard("6. 状态与安全 detach")', 'DebuggerCard("7. 状态与安全 detach")')
replace_exact(
    ui,
    '''                Text("control 审计固定记录 registerWrite=false / memoryWrite=false / breakpoint=false / rawPacket=false。")''',
    '''                Text("control 审计固定记录 registerWrite=false / memoryWrite=false / rawPacket=false；breakpoint 仅允许 typed hardware execution。")''',
)
replace_exact(
    ui,
    '''        "registerWrite=${snapshot.registerWriteCommandSent} memoryWrite=${snapshot.memoryWriteCommandSent} breakpoint=${snapshot.breakpointCommandSent}",''',
    '''        "registerWrite=${snapshot.registerWriteCommandSent} memoryWrite=${snapshot.memoryWriteCommandSent} breakpoint=${snapshot.breakpointCommandSent} hwBreakpoints=${snapshot.breakpoints.size}",''',
)
replace_exact(
    ui,
    '''    appendLine("边界：loopback only；寄存器/内存只读；允许显式授权 typed attach / continue / step / interrupt；无 register write / memory write / breakpoint / raw packet adapter")''',
    '''    appendLine("边界：loopback only；寄存器/内存只读；允许显式授权 typed attach / continue / step / interrupt / AArch64 hardware execution breakpoint；无 software breakpoint / register write / memory write / raw packet adapter")''',
)
replace_exact(
    ui,
    '''    appendLine("breakpointCommandSent=${control.breakpointCommandSent}")
    appendLine("rawPacketAdapterExposed=false")''',
    '''    appendLine("breakpointCommandSent=${control.breakpointCommandSent}")
    appendLine("breakpointSetCommandSent=${control.breakpointSetCommandSent}")
    appendLine("breakpointRemoveCommandSent=${control.breakpointRemoveCommandSent}")
    appendLine("breakpointCount=${control.breakpoints.size}")
    appendLine("rawPacketAdapterExposed=false")''',
)
replace_exact(
    ui,
    '''    if (control.registers.isNotEmpty()) {
        appendLine("registers:")''',
    '''    if (control.breakpoints.isNotEmpty()) {
        appendLine("breakpoints:")
        control.breakpoints.forEach {
            appendLine("  hardware_execution address=0x${it.address.toString(16)} kind=${it.kindBytes}")
        }
    }
    if (control.registers.isNotEmpty()) {
        appendLine("registers:")''',
)

remote_test = "app/src/test/java/com/luckylca/autocrack/runtime/HostDebuggerRemoteClientTest.kt"
replace_exact(
    remote_test,
    '''    @Test
    fun runReplyValidatorAcceptsOnlyStopOrExitPackets() {''',
    '''    @Test
    fun typedHardwareBreakpointPacketsAreFixedAndAddressValidated() {
        assertEquals(
            "Z1,7f12345000,4",
            GdbRemoteBreakpointPacketFactory.insertHardwareExecution(0x7f12345000L),
        )
        assertEquals(
            "z1,7f12345000,4",
            GdbRemoteBreakpointPacketFactory.removeHardwareExecution(0x7f12345000L),
        )
        assertThrows(IllegalArgumentException::class.java) {
            GdbRemoteBreakpointPacketFactory.insertHardwareExecution(0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GdbRemoteBreakpointPacketFactory.insertHardwareExecution(0x1002L)
        }
    }

    @Test
    fun runReplyValidatorAcceptsOnlyStopOrExitPackets() {''',
)
replace_exact(
    remote_test,
    '''    @Test
    fun phase514ClientContainsNoWriteOrBreakpointAdapters() {
        val methodNames = HostDebuggerRemoteClient::class.java.methods.map { method -> method.name }.toSet()
        assertFalse("writeMemory" in methodNames)
        assertFalse("writeRegister" in methodNames)
        assertFalse("insertBreakpoint" in methodNames)
        assertFalse("removeBreakpoint" in methodNames)
        assertFalse("sendRawPacket" in methodNames)
    }''',
    '''    @Test
    fun phase515ClientExposesOnlyTypedHardwareBreakpointAdapters() {
        val methodNames = HostDebuggerRemoteClient::class.java.methods.map { method -> method.name }.toSet()
        assertFalse("writeMemory" in methodNames)
        assertFalse("writeRegister" in methodNames)
        assertFalse("insertBreakpoint" in methodNames)
        assertFalse("removeBreakpoint" in methodNames)
        assertFalse("sendRawPacket" in methodNames)
        assertTrue("setHardwareExecutionBreakpoint" in methodNames)
        assertTrue("removeHardwareExecutionBreakpoint" in methodNames)
    }''',
)

bridge_test = "app/src/test/java/com/luckylca/autocrack/runtime/HostDebuggerControlBridgeTest.kt"
replace_exact(
    bridge_test,
    '''    @Test
    fun controlBridgeExposesNoWriteOrBreakpointMethods() {
        val methodNames = HostDebuggerControlBridge::class.java.methods.map { method -> method.name }.toSet()
        assertEquals(false, "writeMemory" in methodNames)
        assertEquals(false, "writeRegister" in methodNames)
        assertEquals(false, "insertBreakpoint" in methodNames)
        assertEquals(false, "removeBreakpoint" in methodNames)
        assertEquals(false, "sendRawPacket" in methodNames)
    }''',
    '''    @Test
    fun controlBridgeExposesOnlyTypedHardwareBreakpointMethods() {
        val methodNames = HostDebuggerControlBridge::class.java.methods.map { method -> method.name }.toSet()
        assertEquals(false, "writeMemory" in methodNames)
        assertEquals(false, "writeRegister" in methodNames)
        assertEquals(false, "insertBreakpoint" in methodNames)
        assertEquals(false, "removeBreakpoint" in methodNames)
        assertEquals(false, "sendRawPacket" in methodNames)
        assertEquals(true, "setHardwareExecutionBreakpoint" in methodNames)
        assertEquals(true, "removeHardwareExecutionBreakpoint" in methodNames)
    }''',
)

print("Applied AutoCrackApp phase 5.15.0 typed hardware execution breakpoint patch")
