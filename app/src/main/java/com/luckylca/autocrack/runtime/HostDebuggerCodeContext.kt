package com.luckylca.autocrack.runtime

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

data class HostDebuggerStackFrameSnapshot(
    val index: Int,
    val address: Long,
    val framePointer: Long?,
    val modulePath: String,
    val moduleBase: Long,
    val moduleOffset: Long,
    val source: String,
)

data class HostDebuggerStackSnapshot(
    val frames: List<HostDebuggerStackFrameSnapshot>,
    val termination: String,
    val partial: Boolean,
)

data class HostDebuggerCodeContextSnapshot(
    val threadId: String,
    val threadName: String?,
    val pc: Long,
    val lr: Long?,
    val sp: Long?,
    val framePointer: Long?,
    val stack: HostDebuggerStackSnapshot,
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
        val fields = stopReply.split(';')
        fields.firstOrNull { it.startsWith("name:") }
            ?.substringAfter("name:")
            ?.takeIf(String::isNotBlank)
            ?.let { return it }
        val encoded = fields.firstOrNull { it.startsWith("hexname:") }
            ?.substringAfter("hexname:")
            ?.takeIf(String::isNotBlank)
            ?: return null
        return runCatching {
            GdbRemotePacketCodec.decodeHex(encoded).toString(Charsets.UTF_8)
                .trimEnd('\u0000')
                .takeIf(String::isNotBlank)
        }.getOrNull()
    }
}

data class Aarch64FrameRecord(
    val previousFramePointer: Long,
    val savedLinkRegister: Long,
)

object Aarch64FrameRecordDecoder {
    const val FRAME_RECORD_BYTES = 16
    const val FRAME_POINTER_ALIGNMENT = 16L
    const val MAX_FRAME_POINTER_DELTA = 1024L * 1024L

    fun decode(bytes: ByteArray): Aarch64FrameRecord {
        require(bytes.size == FRAME_RECORD_BYTES) { "AArch64 frame record must be exactly 16 bytes" }
        return Aarch64FrameRecord(
            previousFramePointer = littleEndianLong(bytes, 0),
            savedLinkRegister = littleEndianLong(bytes, 8),
        )
    }

    fun validateFramePointer(
        framePointer: Long,
        stackPointer: Long?,
        segments: List<HostDebuggerMemoryMapSegment>,
    ): String? {
        if (framePointer <= 0L) return "frame_pointer_non_positive"
        if (framePointer % FRAME_POINTER_ALIGNMENT != 0L) return "frame_pointer_unaligned"
        if (stackPointer != null && framePointer < stackPointer) return "frame_pointer_below_sp"
        val segment = HostDebuggerMemoryMapParser.findContaining(segments, framePointer)
            ?: return "frame_pointer_unmapped"
        if ('r' !in segment.permissions) return "frame_pointer_not_readable"
        if (segment.endAddressExclusive - framePointer < FRAME_RECORD_BYTES) return "frame_record_crosses_mapping"
        return null
    }

    fun validateNextFramePointer(
        currentFramePointer: Long,
        nextFramePointer: Long,
        stackPointer: Long?,
        segments: List<HostDebuggerMemoryMapSegment>,
    ): String? {
        if (nextFramePointer == 0L) return null
        validateFramePointer(nextFramePointer, stackPointer, segments)?.let { return it }
        if (nextFramePointer <= currentFramePointer) return "frame_pointer_non_monotonic"
        if (nextFramePointer - currentFramePointer > MAX_FRAME_POINTER_DELTA) return "frame_pointer_jump_too_large"
        return null
    }

    private fun littleEndianLong(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        repeat(Long.SIZE_BYTES) { index ->
            value = value or ((bytes[offset + index].toLong() and 0xffL) shl (index * 8))
        }
        return value
    }
}

object HostDebuggerStackFrameResolver {
    fun resolve(
        index: Int,
        address: Long,
        framePointer: Long?,
        source: String,
        segments: List<HostDebuggerMemoryMapSegment>,
    ): HostDebuggerStackFrameSnapshot? {
        if (address <= 0L) return null
        val segment = HostDebuggerMemoryMapParser.findContaining(segments, address) ?: return null
        if (!segment.executable) return null
        return HostDebuggerStackFrameSnapshot(
            index = index,
            address = address,
            framePointer = framePointer,
            modulePath = segment.path.ifBlank { "<anonymous>" },
            moduleBase = segment.loadBase,
            moduleOffset = segment.relativeOffset(address),
            source = source,
        )
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
