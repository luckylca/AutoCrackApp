#!/usr/bin/env python3
from pathlib import Path


def replace_exact(path: str, old: str, new: str, expected: int = 1) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    actual = text.count(old)
    if actual != expected:
        raise SystemExit(f"{path}: expected {expected} occurrence(s), found {actual}: {old[:120]!r}")
    p.write_text(text.replace(old, new), encoding="utf-8")


gradle = "app/build.gradle.kts"
replace_exact(gradle, "versionCode = 52", "versionCode = 53")
replace_exact(
    gradle,
    'versionName = "0.5.15.1-phase5.15-auto-steppable-anchor"',
    'versionName = "0.5.15.2-phase5.15-automatic-code-context"',
)

catalog = "app/src/main/assets/runtime/dynamic-host-tool-catalog-v1.json"
replace_exact(catalog, '"catalogVersion": "0.5.15.1"', '"catalogVersion": "0.5.15.2"')
replace_exact(
    catalog,
    '''        {"id": "auto_steppable_anchor", "changesTargetState": true, "strategy": "prefer-main-thread-current-pc-one-shot-hardware-breakpoint"},
        {"id": "continue", "changesTargetState": true},''',
    '''        {"id": "auto_steppable_anchor", "changesTargetState": true, "strategy": "prefer-main-thread-current-pc-one-shot-hardware-breakpoint"},
        {"id": "automatic_code_context", "changesTargetState": false, "maxInstructionBytes": 36, "sources": ["qRegisterInfo/p", "bounded m", "/proc/<pid>/maps"]},
        {"id": "continue", "changesTargetState": true},''',
)
replace_exact(
    catalog,
    '        "the thread from each continue stop reply is automatically selected as the next register/step context",',
    '        "the thread from each continue stop reply is automatically selected as the next register/step context",\n'
    '        "after a trusted stop, code context is captured read-only from PC/LR/SP, at most 36 nearby instruction bytes, and /proc/<pid>/maps; no target mutation is used for context",',
)

# Lightweight maps-only read so per-step context does not run the full process inspection bundle.
read_bridge = "app/src/main/java/com/luckylca/autocrack/runtime/DynamicHostReadBridge.kt"
replace_exact(
    read_bridge,
    '''    suspend fun inspectProcess(pid: Int): HostProcessInspectionReport {''',
    '''    suspend fun readProcessMaps(pid: Int): CommandResult {
        require(pid > 0) { "PID 必须是正整数" }
        val session = requireRootSession()
        return executeAudited(
            session.executor,
            RootToolCommand.ReadProcessMaps(pid),
            pid,
        )
    }

    suspend fun inspectProcess(pid: Int): HostProcessInspectionReport {''',
)

# Generated helper is intentionally pure/read-only: maps parsing + bounded AArch64 context decoding.
helper = Path("app/src/main/java/com/luckylca/autocrack/runtime/HostDebuggerCodeContext.kt")
if helper.exists():
    raise SystemExit(f"{helper}: already exists")
helper.write_text(r'''package com.luckylca.autocrack.runtime

data class HostDebuggerMemoryMapSegment(
    val startAddress: Long,
    val endAddressExclusive: Long,
    val permissions: String,
    val fileOffset: Long,
    val path: String,
) {
    val executable: Boolean
        get() = 'x' in permissions

    val loadBase: Long
        get() = if (fileOffset <= startAddress) startAddress - fileOffset else startAddress

    fun contains(address: Long): Boolean = address in startAddress until endAddressExclusive

    fun relativeOffset(address: Long): Long = address - loadBase
}

data class HostDebuggerInstructionSnapshot(
    val address: Long,
    val rawHex: String,
    val text: String,
    val current: Boolean,
)

data class HostDebuggerCodeContextSnapshot(
    val threadId: String,
    val threadName: String?,
    val pc: Long,
    val lr: Long?,
    val sp: Long?,
    val modulePath: String,
    val moduleBase: Long,
    val moduleOffset: Long,
    val segmentStart: Long,
    val segmentEndExclusive: Long,
    val segmentPermissions: String,
    val segmentFileOffset: Long,
    val memoryStartAddress: Long,
    val memoryHex: String,
    val instructions: List<HostDebuggerInstructionSnapshot>,
    val decoder: String = "builtin-aarch64-control-flow-lite",
)

object HostDebuggerMemoryMapParser {
    fun parse(output: String): List<HostDebuggerMemoryMapSegment> = output
        .lineSequence()
        .mapNotNull { line ->
            val match = MAPS_REGEX.matchEntire(line) ?: return@mapNotNull null
            val start = match.groupValues[1].toLongOrNull(16) ?: return@mapNotNull null
            val end = match.groupValues[2].toLongOrNull(16) ?: return@mapNotNull null
            val offset = match.groupValues[4].toLongOrNull(16) ?: return@mapNotNull null
            if (start < 0L || end <= start || offset < 0L) return@mapNotNull null
            HostDebuggerMemoryMapSegment(
                startAddress = start,
                endAddressExclusive = end,
                permissions = match.groupValues[3],
                fileOffset = offset,
                path = match.groupValues[5].trim(),
            )
        }
        .toList()

    fun findContaining(
        segments: List<HostDebuggerMemoryMapSegment>,
        address: Long,
    ): HostDebuggerMemoryMapSegment? = segments.firstOrNull { it.contains(address) }

    private val MAPS_REGEX = Regex(
        "^([0-9a-fA-F]+)-([0-9a-fA-F]+)\\s+([rwxps-]{4})\\s+" +
            "([0-9a-fA-F]+)\\s+\\S+\\s+\\d+\\s*(.*)$",
    )
}

object HostDebuggerStopReplyDetails {
    fun threadName(stopReply: String): String? {
        if (!stopReply.startsWith('T')) return null
        return stopReply.split(';')
            .firstOrNull { it.startsWith("name:") }
            ?.substringAfter("name:")
            ?.takeIf(String::isNotBlank)
    }
}

object Aarch64InstructionContextDecoder {
    fun decodeWindow(
        startAddress: Long,
        bytes: ByteArray,
        currentPc: Long,
    ): List<HostDebuggerInstructionSnapshot> {
        require(startAddress >= 0L && startAddress % 4L == 0L) {
            "AArch64 instruction window must start at a 4-byte-aligned address"
        }
        val count = bytes.size / 4
        return (0 until count).map { index ->
            val byteOffset = index * 4
            val address = startAddress + byteOffset
            val word = littleEndianWord(bytes, byteOffset)
            HostDebuggerInstructionSnapshot(
                address = address,
                rawHex = bytes.copyOfRange(byteOffset, byteOffset + 4)
                    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) },
                text = decode(word, address),
                current = address == currentPc,
            )
        }
    }

    fun decode(word: Long, address: Long): String {
        val op = word and 0xffff_ffffL
        return when {
            op == 0xd503201fL -> "nop"
            (op and 0xfffffc1fL) == 0xd65f0000L -> {
                val rn = ((op shr 5) and 0x1fL).toInt()
                if (rn == 30) "ret" else "ret x$rn"
            }
            (op and 0xfffffc1fL) == 0xd61f0000L ->
                "br x${((op shr 5) and 0x1fL).toInt()}"
            (op and 0xfffffc1fL) == 0xd63f0000L ->
                "blr x${((op shr 5) and 0x1fL).toInt()}"
            (op and 0xffe0001fL) == 0xd4000001L ->
                "svc #${((op shr 5) and 0xffffL)}"
            (op and 0xffe0001fL) == 0xd4200000L ->
                "brk #${((op shr 5) and 0xffffL)}"
            (op and 0xfc000000L) == 0x14000000L -> {
                val delta = signExtend(op and 0x03ffffffL, 26) shl 2
                "b 0x${(address + delta).toString(16)}"
            }
            (op and 0xfc000000L) == 0x94000000L -> {
                val delta = signExtend(op and 0x03ffffffL, 26) shl 2
                "bl 0x${(address + delta).toString(16)}"
            }
            (op and 0xff000010L) == 0x54000000L -> {
                val delta = signExtend((op shr 5) and 0x7ffffL, 19) shl 2
                val condition = CONDITIONS[(op and 0xfL).toInt()]
                "b.$condition 0x${(address + delta).toString(16)}"
            }
            (op and 0x7e000000L) == 0x34000000L -> {
                val nonZero = (op and 0x01000000L) != 0L
                val is64 = (op and 0x80000000L) != 0L
                val delta = signExtend((op shr 5) and 0x7ffffL, 19) shl 2
                val rt = (op and 0x1fL).toInt()
                "${if (nonZero) "cbnz" else "cbz"} ${if (is64) "x" else "w"}$rt, 0x${(address + delta).toString(16)}"
            }
            (op and 0x7e000000L) == 0x36000000L -> {
                val nonZero = (op and 0x01000000L) != 0L
                val bit = (((op shr 31) and 1L) shl 5) or ((op shr 19) and 0x1fL)
                val delta = signExtend((op shr 5) and 0x3fffL, 14) shl 2
                val rt = (op and 0x1fL).toInt()
                "${if (nonZero) "tbnz" else "tbz"} x$rt, #$bit, 0x${(address + delta).toString(16)}"
            }
            else -> ".inst 0x${op.toString(16).padStart(8, '0')}"
        }
    }

    private fun littleEndianWord(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xffL) or
            ((bytes[offset + 1].toLong() and 0xffL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xffL) shl 24)

    private fun signExtend(value: Long, bits: Int): Long {
        val shift = Long.SIZE_BITS - bits
        return (value shl shift) shr shift
    }

    private val CONDITIONS = listOf(
        "eq", "ne", "cs", "cc", "mi", "pl", "vs", "vc",
        "hi", "ls", "ge", "lt", "gt", "le", "al", "nv",
    )
}
''', encoding="utf-8")

# Unit tests for maps translation and the built-in high-value AArch64 decoder.
helper_test = Path("app/src/test/java/com/luckylca/autocrack/runtime/HostDebuggerCodeContextTest.kt")
if helper_test.exists():
    raise SystemExit(f"{helper_test}: already exists")
helper_test.write_text(r'''package com.luckylca.autocrack.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostDebuggerCodeContextTest {
    @Test
    fun mapsParserPreservesFileOffsetForModuleRelativeAddress() {
        val segments = HostDebuggerMemoryMapParser.parse(
            "7da9e65000-7da9e70000 r-xp 00015000 00:01 123 /apex/test/lib64/libfoo.so",
        )
        val segment = HostDebuggerMemoryMapParser.findContaining(segments, 0x7da9e6688cL)!!
        assertEquals(0x7da9e50000L, segment.loadBase)
        assertEquals(0x16688cL, segment.relativeOffset(0x7da9e6688cL))
        assertTrue(segment.executable)
    }

    @Test
    fun decodesImportantAarch64ControlFlowWithoutExternalToolpack() {
        assertEquals("nop", Aarch64InstructionContextDecoder.decode(0xd503201fL, 0x1000L))
        assertEquals("ret", Aarch64InstructionContextDecoder.decode(0xd65f03c0L, 0x1000L))
        assertEquals("b 0x1008", Aarch64InstructionContextDecoder.decode(0x14000002L, 0x1000L))
        assertEquals("bl 0x1008", Aarch64InstructionContextDecoder.decode(0x94000002L, 0x1000L))
    }
}
''', encoding="utf-8")

bridge = "app/src/main/java/com/luckylca/autocrack/runtime/HostDebuggerControlBridge.kt"
replace_exact(
    bridge,
    '''class HostDebuggerControlBridge(
    private val manager: HostDebuggerSessionManager,
) {''',
    '''class HostDebuggerControlBridge(
    private val manager: HostDebuggerSessionManager,
    private val readBridge: DynamicHostReadBridge,
) {''',
)
replace_exact(
    bridge,
    '''    val autoAnchorAddress: Long?,
    val autoAnchorStopObserved: Boolean,
    val registers: List<HostDebuggerRegisterSnapshot>,''',
    '''    val autoAnchorAddress: Long?,
    val autoAnchorStopObserved: Boolean,
    val codeContext: HostDebuggerCodeContextSnapshot?,
    val codeContextFailure: String?,
    val registers: List<HostDebuggerRegisterSnapshot>,''',
)
replace_exact(
    bridge,
    '''        autoAnchorAddress = mutable.autoAnchorAddress,
        autoAnchorStopObserved = mutable.autoAnchorStopObserved,
        registers = mutable.registers,''',
    '''        autoAnchorAddress = mutable.autoAnchorAddress,
        autoAnchorStopObserved = mutable.autoAnchorStopObserved,
        codeContext = mutable.codeContext,
        codeContextFailure = mutable.codeContextFailure,
        registers = mutable.registers,''',
)
replace_exact(
    bridge,
    '''        var autoAnchorOwnsBreakpoint: Boolean = false,
        var autoAnchorStopObserved: Boolean = false,
        var registers: List<HostDebuggerRegisterSnapshot> = emptyList(),''',
    '''        var autoAnchorOwnsBreakpoint: Boolean = false,
        var autoAnchorStopObserved: Boolean = false,
        var codeContext: HostDebuggerCodeContextSnapshot? = null,
        var codeContextFailure: String? = null,
        var registers: List<HostDebuggerRegisterSnapshot> = emptyList(),''',
)

# Capture context after a real single-step completes.
replace_exact(
    bridge,
    '''            val stopReply = activeClient.step(stepThreadId)
            runCatching { activeClient.selectGeneralThread(stepThreadId) }
            synchronized(lock) {
                mutable.lastStopReply = stopReply
                mutable.stepCompleted = true''',
    '''            val stopReply = activeClient.step(stepThreadId)
            runCatching { activeClient.selectGeneralThread(stepThreadId) }
            val contextResult = runCatching {
                captureCodeContext(activeClient, stepThreadId, stopReply)
            }
            synchronized(lock) {
                mutable.lastStopReply = stopReply
                mutable.codeContext = contextResult.getOrNull()
                mutable.codeContextFailure = contextResult.exceptionOrNull()?.message
                mutable.stepCompleted = true''',
)

# Capture context after any Continue stop, including the one-shot auto anchor hit.
replace_exact(
    bridge,
    '''                    synchronized(lock) {
                        mutable.lastStopReply = stopReply
                        if (stoppedThreadId != null) mutable.selectedThreadId = stoppedThreadId
                        if (autoRemoved && autoAnchor.first != null) {''',
    '''                    val contextThreadId = stoppedThreadId ?: synchronized(lock) { mutable.selectedThreadId }
                    val contextResult = if (contextThreadId != null) {
                        runCatching { captureCodeContext(activeClient, contextThreadId, stopReply) }
                    } else {
                        Result.failure(IllegalStateException("stop reply 没有可用于代码上下文的线程"))
                    }

                    synchronized(lock) {
                        mutable.lastStopReply = stopReply
                        if (stoppedThreadId != null) mutable.selectedThreadId = stoppedThreadId
                        mutable.codeContext = contextResult.getOrNull()
                        mutable.codeContextFailure = contextResult.exceptionOrNull()?.message
                        if (autoRemoved && autoAnchor.first != null) {''',
)

# Add the private read-only capture routine before the attach-confirm helper.
replace_exact(
    bridge,
    '''    private suspend fun awaitConfirmedAttach(sessionId: String): HostDebuggerSessionSnapshot? {''',
    '''    private suspend fun captureCodeContext(
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

        return HostDebuggerCodeContextSnapshot(
            threadId = normalizedThreadId,
            threadName = HostDebuggerStopReplyDetails.threadName(stopReply)
                ?: synchronized(lock) { mutable.threads.firstOrNull { it.id == normalizedThreadId }?.name },
            pc = pc,
            lr = lr,
            sp = sp,
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

    private suspend fun awaitConfirmedAttach(sessionId: String): HostDebuggerSessionSnapshot? {''',
)

replace_exact(
    bridge,
    '''                .put("autoAnchorStopObserved", mutable.autoAnchorStopObserved)
                .put("rawPacketAdapterExposed", false)''',
    '''                .put("autoAnchorStopObserved", mutable.autoAnchorStopObserved)
                .put("codeContextCaptured", mutable.codeContext != null)
                .put("codeContextPc", mutable.codeContext?.pc ?: JSONObject.NULL)
                .put("codeContextModule", mutable.codeContext?.modulePath ?: JSONObject.NULL)
                .put("codeContextFailure", mutable.codeContextFailure ?: JSONObject.NULL)
                .put("rawPacketAdapterExposed", false)''',
)
replace_exact(
    bridge,
    '''        const val AUTO_REGISTER_METADATA_LIMIT = 64
        const val AARCH64_PC_REGISTER_FALLBACK_INDEX = 32''',
    '''        const val AUTO_REGISTER_METADATA_LIMIT = 64
        const val AARCH64_PC_REGISTER_FALLBACK_INDEX = 32
        const val CODE_CONTEXT_REGISTER_METADATA_LIMIT = 64
        const val AARCH64_LR_REGISTER_FALLBACK_INDEX = 30
        const val AARCH64_SP_REGISTER_FALLBACK_INDEX = 31
        const val AARCH64_INSTRUCTION_BYTES = 4L
        const val CODE_CONTEXT_BYTES_BEFORE = 16
        const val CODE_CONTEXT_BYTES_AFTER = 20L
        const val CODE_CONTEXT_MAX_BYTES = 36''',
)

# Wire the existing read-only host bridge into the debugger controller.
app = "app/src/main/java/com/luckylca/autocrack/ui/App.kt"
replace_exact(
    app,
    '''    val debuggerControlBridge = remember(debuggerSessionManager) {
        HostDebuggerControlBridge(debuggerSessionManager)
    }''',
    '''    val debuggerControlBridge = remember(debuggerSessionManager, dynamicReadBridge) {
        HostDebuggerControlBridge(debuggerSessionManager, dynamicReadBridge)
    }''',
)

ui = "app/src/main/java/com/luckylca/autocrack/ui/DebuggerSessionScreen.kt"
replace_exact(
    ui,
    '''        item {
            DebuggerCard("5. 受控硬件执行断点") {''',
    '''        item {
            DebuggerCard("5. 自动代码上下文") {
                Text(
                    "每次 Continue stop 或真正完成 STEP 后，AutoCrack 自动读取 PC/LR/SP、/proc/<pid>/maps 和最多 36B 的 PC 附近代码。这里只读，不写目标。",
                    style = MaterialTheme.typography.bodySmall,
                )
                controlSnapshot.codeContext?.let { code ->
                    Text(
                        "thread=${code.threadId} name=${code.threadName ?: "未知"}",
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        "PC=0x${code.pc.toString(16)} LR=${code.lr?.let { "0x${it.toString(16)}" } ?: "无"} SP=${code.sp?.let { "0x${it.toString(16)}" } ?: "无"}",
                        fontFamily = FontFamily.Monospace,
                    )
                    SelectionContainer {
                        Text("module=${code.modulePath}", fontFamily = FontFamily.Monospace)
                    }
                    Text(
                        "base=0x${code.moduleBase.toString(16)} +0x${code.moduleOffset.toString(16)} segment=0x${code.segmentStart.toString(16)}-0x${code.segmentEndExclusive.toString(16)} ${code.segmentPermissions} fileOff=0x${code.segmentFileOffset.toString(16)}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text("指令上下文（${code.decoder}）", fontWeight = FontWeight.SemiBold)
                    code.instructions.forEach { instruction ->
                        Text(
                            "${if (instruction.current) "→" else " "} 0x${instruction.address.toString(16)}  ${instruction.rawHex}  ${instruction.text}",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (instruction.current) FontWeight.Bold else FontWeight.Normal,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } ?: Text("尚未捕获：先使用自动运行/Continue 命中一次 stop，或完成一次 STEP。")
                controlSnapshot.codeContextFailure?.let { failure ->
                    Text("codeContextFailure=$failure", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        item {
            DebuggerCard("6. 受控硬件执行断点") {''',
)
replace_exact(ui, 'DebuggerCard("6. 自动进入可单步位置 / 执行控制")', 'DebuggerCard("7. 自动进入可单步位置 / 执行控制")')
replace_exact(ui, 'DebuggerCard("7. 状态与安全 detach")', 'DebuggerCard("8. 状态与安全 detach")')
replace_exact(
    ui,
    '''    appendLine("autoAnchorStopObserved=${control.autoAnchorStopObserved}")
    appendLine("rawPacketAdapterExposed=false")''',
    '''    appendLine("autoAnchorStopObserved=${control.autoAnchorStopObserved}")
    appendLine("codeContextCaptured=${control.codeContext != null}")
    appendLine("codeContextFailure=${control.codeContextFailure ?: "无"}")
    control.codeContext?.let { code ->
        appendLine("codeThreadId=${code.threadId} codeThreadName=${code.threadName ?: "无"}")
        appendLine("codePC=0x${code.pc.toString(16)} codeLR=${code.lr?.let { "0x${it.toString(16)}" } ?: "无"} codeSP=${code.sp?.let { "0x${it.toString(16)}" } ?: "无"}")
        appendLine("codeModule=${code.modulePath}")
        appendLine("codeModuleBase=0x${code.moduleBase.toString(16)} codeModuleOffset=0x${code.moduleOffset.toString(16)}")
        appendLine("codeSegment=0x${code.segmentStart.toString(16)}-0x${code.segmentEndExclusive.toString(16)} perms=${code.segmentPermissions} fileOffset=0x${code.segmentFileOffset.toString(16)}")
        appendLine("codeDecoder=${code.decoder}")
        appendLine("instructions:")
        code.instructions.forEach { instruction ->
            appendLine("  ${if (instruction.current) "->" else "  "} 0x${instruction.address.toString(16)} ${instruction.rawHex} ${instruction.text}")
        }
    }
    appendLine("rawPacketAdapterExposed=false")''',
)

print("Applied AutoCrackApp phase 5.15.2 automatic code-context patch")
