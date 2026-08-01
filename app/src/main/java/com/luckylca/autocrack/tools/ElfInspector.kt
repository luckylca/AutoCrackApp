package com.luckylca.autocrack.tools

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

class ElfInspector {
    fun inspect(bytes: ByteArray, sourceLabel: String): ElfAnalysisReport {
        if (bytes.size < ELF_IDENT_SIZE) {
            throw AnalysisToolException("ELF 文件过短：${bytes.size} B")
        }
        if (!hasElfMagic(bytes)) {
            throw AnalysisToolException("目标条目不是有效 ELF：缺少 7F 45 4C 46 魔数")
        }

        val is64Bit = when (bytes[4].toInt() and 0xff) {
            ELFCLASS32 -> false
            ELFCLASS64 -> true
            else -> throw AnalysisToolException("不支持的 ELF EI_CLASS：${bytes[4].toInt() and 0xff}")
        }
        val byteOrder = when (bytes[5].toInt() and 0xff) {
            ELFDATA2LSB -> ByteOrder.LITTLE_ENDIAN
            ELFDATA2MSB -> ByteOrder.BIG_ENDIAN
            else -> throw AnalysisToolException("不支持的 ELF EI_DATA：${bytes[5].toInt() and 0xff}")
        }
        val reader = ElfReader(bytes, byteOrder)
        val headerSize = if (is64Bit) ELF64_HEADER_SIZE else ELF32_HEADER_SIZE
        if (bytes.size < headerSize) {
            throw AnalysisToolException("ELF 头不完整：需要 $headerSize B，实际 ${bytes.size} B")
        }

        val objectTypeCode = reader.u16(16)
        val machineCode = reader.u16(18)
        val entryPoint = if (is64Bit) reader.nonNegativeLong(24) else reader.u32(24)
        val programHeaderOffset = if (is64Bit) reader.nonNegativeLong(32) else reader.u32(28)
        val sectionHeaderOffset = if (is64Bit) reader.nonNegativeLong(40) else reader.u32(32)
        val programHeaderEntrySize = reader.u16(if (is64Bit) 54 else 42)
        val programHeaderCount = reader.u16(if (is64Bit) 56 else 44)
        val sectionHeaderEntrySize = reader.u16(if (is64Bit) 58 else 46)
        val sectionHeaderCount = reader.u16(if (is64Bit) 60 else 48)
        val sectionNameIndex = reader.u16(if (is64Bit) 62 else 50)
        val diagnostics = mutableListOf<String>()

        if (sectionHeaderCount == 0 && sectionHeaderOffset != 0L) {
            diagnostics += "暂未解析扩展节区数量（e_shnum=0）"
        }
        if (programHeaderCount == PN_XNUM) {
            diagnostics += "暂未解析扩展 Program Header 数量（PN_XNUM）"
        }

        val rawSections = parseSections(
            reader = reader,
            is64Bit = is64Bit,
            tableOffset = sectionHeaderOffset,
            entrySize = sectionHeaderEntrySize,
            count = sectionHeaderCount,
            diagnostics = diagnostics,
        )
        val namedSections = resolveSectionNames(reader, rawSections, sectionNameIndex, diagnostics)
        val segments = parseSegments(
            reader = reader,
            is64Bit = is64Bit,
            tableOffset = programHeaderOffset,
            entrySize = programHeaderEntrySize,
            count = programHeaderCount,
            diagnostics = diagnostics,
        )
        val dynamicInfo = parseDynamicInfo(reader, is64Bit, namedSections, diagnostics)
        val symbols = parseSymbols(reader, is64Bit, namedSections, diagnostics)
        val buildId = parseBuildId(reader, namedSections, diagnostics)
        val interestingStrings = scanInterestingStrings(bytes)

        val allSymbolNames = (symbols.imported + symbols.exported).distinct()
        val jniSymbols = allSymbolNames
            .filter { symbol ->
                symbol == "JNI_OnLoad" ||
                    symbol.startsWith("Java_") ||
                    symbol.contains("RegisterNatives", ignoreCase = true)
            }
            .take(MAX_JNI_SYMBOLS)

        val stackSegment = segments.firstOrNull { it.typeCode == PT_GNU_STACK }
        val nx = when {
            stackSegment == null -> "UNKNOWN"
            stackSegment.flags and PF_X == 0L -> "ENABLED"
            else -> "DISABLED"
        }
        val hasRelro = segments.any { it.typeCode == PT_GNU_RELRO }
        val relro = when {
            hasRelro && dynamicInfo.bindNow -> "FULL"
            hasRelro -> "PARTIAL"
            else -> "NONE"
        }
        val hasStackCanary = allSymbolNames.any { it == "__stack_chk_fail" } ||
            interestingStrings.any { it.contains("__stack_chk_fail") }
        val hasFortifiedFunctions = allSymbolNames.any { symbol ->
            symbol.endsWith("_chk") || symbol.startsWith("__") && symbol.contains("_chk")
        }
        val hasSymtab = namedSections.any { it.typeCode == SHT_SYMTAB || it.name == ".symtab" }

        return ElfAnalysisReport(
            sourceLabel = sourceLabel,
            fileSizeBytes = bytes.size.toLong(),
            elfClass = if (is64Bit) "ELF64" else "ELF32",
            byteOrder = if (byteOrder == ByteOrder.LITTLE_ENDIAN) "Little Endian" else "Big Endian",
            objectType = objectTypeName(objectTypeCode),
            machine = machineName(machineCode),
            entryPoint = entryPoint,
            buildId = buildId,
            soname = dynamicInfo.soname,
            neededLibraries = dynamicInfo.needed.distinct().sorted(),
            rpath = dynamicInfo.rpath,
            runpath = dynamicInfo.runpath,
            sections = namedSections
                .take(MAX_VISIBLE_SECTIONS)
                .map { section ->
                    ElfSectionSummary(
                        index = section.index,
                        name = section.name,
                        type = sectionTypeName(section.typeCode),
                        offset = section.offset,
                        size = section.size,
                        flags = section.flags,
                    )
                },
            segments = segments
                .take(MAX_VISIBLE_SEGMENTS)
                .map { segment ->
                    ElfSegmentSummary(
                        index = segment.index,
                        type = programTypeName(segment.typeCode),
                        offset = segment.offset,
                        fileSize = segment.fileSize,
                        memorySize = segment.memorySize,
                        flags = programFlags(segment.flags),
                    )
                },
            importedSymbols = symbols.imported.distinct().sorted().take(MAX_VISIBLE_SYMBOLS),
            exportedSymbols = symbols.exported.distinct().sorted().take(MAX_VISIBLE_SYMBOLS),
            jniSymbols = jniSymbols,
            interestingStrings = interestingStrings.take(MAX_VISIBLE_STRINGS),
            hardening = ElfHardeningSummary(
                nx = nx,
                relro = relro,
                bindNow = dynamicInfo.bindNow,
                stackCanary = hasStackCanary,
                fortifiedFunctions = hasFortifiedFunctions,
                stripped = !hasSymtab,
                positionIndependent = objectTypeCode == ET_DYN,
            ),
            diagnostics = diagnostics.distinct(),
        )
    }

    private fun parseSections(
        reader: ElfReader,
        is64Bit: Boolean,
        tableOffset: Long,
        entrySize: Int,
        count: Int,
        diagnostics: MutableList<String>,
    ): List<RawSection> {
        if (tableOffset == 0L || count == 0) return emptyList()
        val minimumEntrySize = if (is64Bit) ELF64_SECTION_SIZE else ELF32_SECTION_SIZE
        if (entrySize < minimumEntrySize) {
            diagnostics += "Section Header 条目过短：$entrySize B"
            return emptyList()
        }
        val boundedCount = min(count, MAX_SECTION_COUNT)
        if (count > boundedCount) diagnostics += "节区数量过多，仅解析前 $boundedCount 个"

        return buildList {
            repeat(boundedCount) { index ->
                val base = checkedTableOffset(tableOffset, entrySize, index, reader.size) ?: run {
                    diagnostics += "Section Header #$index 越界，已停止解析"
                    return@buildList
                }
                val section = if (is64Bit) {
                    RawSection(
                        index = index,
                        nameOffset = reader.u32(base).toInt(),
                        typeCode = reader.u32(base + 4).toInt(),
                        flags = reader.nonNegativeLong(base + 8),
                        offset = reader.nonNegativeLong(base + 24),
                        size = reader.nonNegativeLong(base + 32),
                        link = reader.u32(base + 40).toInt(),
                        entrySize = reader.nonNegativeLong(base + 56),
                    )
                } else {
                    RawSection(
                        index = index,
                        nameOffset = reader.u32(base).toInt(),
                        typeCode = reader.u32(base + 4).toInt(),
                        flags = reader.u32(base + 8),
                        offset = reader.u32(base + 16),
                        size = reader.u32(base + 20),
                        link = reader.u32(base + 24).toInt(),
                        entrySize = reader.u32(base + 36),
                    )
                }
                add(section)
            }
        }
    }

    private fun resolveSectionNames(
        reader: ElfReader,
        sections: List<RawSection>,
        sectionNameIndex: Int,
        diagnostics: MutableList<String>,
    ): List<NamedSection> {
        if (sections.isEmpty()) return emptyList()
        val stringSection = sections.getOrNull(sectionNameIndex)
        if (stringSection == null) {
            diagnostics += "e_shstrndx=$sectionNameIndex 无效，节区名称不可用"
        }
        return sections.map { section ->
            val name = if (stringSection == null) {
                "<section-${section.index}>"
            } else {
                reader.cString(stringSection.offset, stringSection.size, section.nameOffset)
                    .ifBlank { "<section-${section.index}>" }
            }
            NamedSection(
                index = section.index,
                name = name,
                typeCode = section.typeCode,
                flags = section.flags,
                offset = section.offset,
                size = section.size,
                link = section.link,
                entrySize = section.entrySize,
            )
        }
    }

    private fun parseSegments(
        reader: ElfReader,
        is64Bit: Boolean,
        tableOffset: Long,
        entrySize: Int,
        count: Int,
        diagnostics: MutableList<String>,
    ): List<RawSegment> {
        if (tableOffset == 0L || count == 0 || count == PN_XNUM) return emptyList()
        val minimumEntrySize = if (is64Bit) ELF64_PROGRAM_SIZE else ELF32_PROGRAM_SIZE
        if (entrySize < minimumEntrySize) {
            diagnostics += "Program Header 条目过短：$entrySize B"
            return emptyList()
        }
        val boundedCount = min(count, MAX_PROGRAM_COUNT)
        if (count > boundedCount) diagnostics += "Program Header 数量过多，仅解析前 $boundedCount 个"

        return buildList {
            repeat(boundedCount) { index ->
                val base = checkedTableOffset(tableOffset, entrySize, index, reader.size) ?: run {
                    diagnostics += "Program Header #$index 越界，已停止解析"
                    return@buildList
                }
                val segment = if (is64Bit) {
                    RawSegment(
                        index = index,
                        typeCode = reader.u32(base).toInt(),
                        flags = reader.u32(base + 4),
                        offset = reader.nonNegativeLong(base + 8),
                        fileSize = reader.nonNegativeLong(base + 32),
                        memorySize = reader.nonNegativeLong(base + 40),
                    )
                } else {
                    RawSegment(
                        index = index,
                        typeCode = reader.u32(base).toInt(),
                        flags = reader.u32(base + 24),
                        offset = reader.u32(base + 4),
                        fileSize = reader.u32(base + 16),
                        memorySize = reader.u32(base + 20),
                    )
                }
                add(segment)
            }
        }
    }

    private fun parseDynamicInfo(
        reader: ElfReader,
        is64Bit: Boolean,
        sections: List<NamedSection>,
        diagnostics: MutableList<String>,
    ): DynamicInfo {
        val dynamicSection = sections.firstOrNull { it.typeCode == SHT_DYNAMIC || it.name == ".dynamic" }
            ?: return DynamicInfo()
        val stringSection = sections.getOrNull(dynamicSection.link)
        if (stringSection == null) {
            diagnostics += "Dynamic Section 的字符串表索引无效：${dynamicSection.link}"
            return DynamicInfo()
        }
        val defaultEntrySize = if (is64Bit) ELF64_DYNAMIC_SIZE else ELF32_DYNAMIC_SIZE
        val entrySize = dynamicSection.entrySize.takeIf { it >= defaultEntrySize } ?: defaultEntrySize.toLong()
        val possibleCount = (dynamicSection.size / entrySize).coerceAtMost(MAX_DYNAMIC_ENTRIES.toLong()).toInt()
        val neededOffsets = mutableListOf<Long>()
        var sonameOffset: Long? = null
        var rpathOffset: Long? = null
        var runpathOffset: Long? = null
        var bindNow = false

        repeat(possibleCount) { index ->
            val base = checkedTableOffset(dynamicSection.offset, entrySize.toInt(), index, reader.size)
                ?: return@repeat
            val tag = if (is64Bit) reader.s64(base) else reader.s32(base).toLong()
            val value = if (is64Bit) reader.nonNegativeLong(base + 8) else reader.u32(base + 4)
            when (tag) {
                DT_NULL -> return@repeat
                DT_NEEDED -> neededOffsets += value
                DT_SONAME -> sonameOffset = value
                DT_RPATH -> rpathOffset = value
                DT_RUNPATH -> runpathOffset = value
                DT_BIND_NOW -> bindNow = true
                DT_FLAGS -> if (value and DF_BIND_NOW != 0L) bindNow = true
                DT_FLAGS_1 -> if (value and DF_1_NOW != 0L) bindNow = true
            }
        }

        fun readDynamicString(offset: Long?): String? = offset?.let { value ->
            if (value > Int.MAX_VALUE) return@let null
            reader.cString(stringSection.offset, stringSection.size, value.toInt()).ifBlank { null }
        }

        return DynamicInfo(
            needed = neededOffsets.mapNotNull(::readDynamicString),
            soname = readDynamicString(sonameOffset),
            rpath = readDynamicString(rpathOffset),
            runpath = readDynamicString(runpathOffset),
            bindNow = bindNow,
        )
    }

    private fun parseSymbols(
        reader: ElfReader,
        is64Bit: Boolean,
        sections: List<NamedSection>,
        diagnostics: MutableList<String>,
    ): SymbolCollection {
        val imported = linkedSetOf<String>()
        val exported = linkedSetOf<String>()
        val symbolSections = sections.filter { section ->
            section.typeCode == SHT_DYNSYM || section.typeCode == SHT_SYMTAB
        }

        symbolSections.forEach { symbolSection ->
            val stringSection = sections.getOrNull(symbolSection.link)
            if (stringSection == null) {
                diagnostics += "${symbolSection.name} 的字符串表索引无效：${symbolSection.link}"
                return@forEach
            }
            val defaultEntrySize = if (is64Bit) ELF64_SYMBOL_SIZE else ELF32_SYMBOL_SIZE
            val entrySize = symbolSection.entrySize.takeIf { it >= defaultEntrySize } ?: defaultEntrySize.toLong()
            val count = (symbolSection.size / entrySize).coerceAtMost(MAX_SYMBOL_ENTRIES.toLong()).toInt()
            repeat(count) { index ->
                val base = checkedTableOffset(symbolSection.offset, entrySize.toInt(), index, reader.size)
                    ?: return@repeat
                val nameOffset = reader.u32(base).toInt()
                val infoOffset = if (is64Bit) base + 4 else base + 12
                val sectionIndexOffset = if (is64Bit) base + 6 else base + 14
                val info = reader.u8(infoOffset)
                val sectionIndex = reader.u16(sectionIndexOffset)
                val binding = info ushr 4
                if (binding != STB_GLOBAL && binding != STB_WEAK) return@repeat
                val name = reader.cString(stringSection.offset, stringSection.size, nameOffset)
                if (name.isBlank()) return@repeat
                if (sectionIndex == SHN_UNDEF) {
                    if (imported.size < MAX_COLLECTED_SYMBOLS) imported += name
                } else if (exported.size < MAX_COLLECTED_SYMBOLS) {
                    exported += name
                }
            }
        }
        return SymbolCollection(imported.toList(), exported.toList())
    }

    private fun parseBuildId(
        reader: ElfReader,
        sections: List<NamedSection>,
        diagnostics: MutableList<String>,
    ): String? {
        val noteSections = sections.filter { section ->
            section.typeCode == SHT_NOTE || section.name == ".note.gnu.build-id"
        }
        noteSections.forEach { section ->
            var cursor = section.offset
            val end = safeEnd(section.offset, section.size, reader.size) ?: return@forEach
            var parsedNotes = 0
            while (cursor + NOTE_HEADER_SIZE <= end && parsedNotes < MAX_NOTES) {
                val cursorOffset = cursor.toInt()
                val nameSize = reader.u32(cursorOffset)
                val descriptionSize = reader.u32(cursorOffset + 4)
                val type = reader.u32(cursorOffset + 8)
                val nameStart = cursor + NOTE_HEADER_SIZE
                val nameEnd = nameStart + nameSize
                val descriptionStart = align4(nameEnd)
                val descriptionEnd = descriptionStart + descriptionSize
                if (nameEnd > end || descriptionEnd > end) break
                val name = reader.ascii(nameStart, nameSize.toInt()).trimEnd('\u0000')
                if (name == "GNU" && type == NT_GNU_BUILD_ID.toLong()) {
                    return reader.hex(descriptionStart, descriptionSize.toInt())
                }
                cursor = align4(descriptionEnd)
                parsedNotes += 1
            }
        }
        if (noteSections.any { it.name == ".note.gnu.build-id" }) {
            diagnostics += "存在 .note.gnu.build-id，但没有解析到有效 GNU Build ID"
        }
        return null
    }

    private fun scanInterestingStrings(bytes: ByteArray): List<String> {
        val result = linkedSetOf<String>()
        val builder = StringBuilder()

        fun flush() {
            if (builder.length >= MIN_STRING_LENGTH) {
                val value = builder.toString().take(MAX_STRING_LENGTH)
                val lower = value.lowercase()
                if (INTERESTING_STRING_MARKERS.any(lower::contains)) {
                    result += value
                }
            }
            builder.setLength(0)
        }

        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            if (value in PRINTABLE_ASCII_RANGE) {
                if (builder.length < MAX_STRING_LENGTH) builder.append(value.toChar())
            } else {
                flush()
                if (result.size >= MAX_COLLECTED_STRINGS) break
            }
        }
        flush()
        return result.toList()
    }

    private fun checkedTableOffset(tableOffset: Long, entrySize: Int, index: Int, fileSize: Int): Int? {
        if (tableOffset < 0 || entrySize <= 0 || index < 0) return null
        val offset = tableOffset + entrySize.toLong() * index
        if (offset < 0 || offset > Int.MAX_VALUE) return null
        if (offset + entrySize > fileSize.toLong()) return null
        return offset.toInt()
    }

    private fun safeEnd(offset: Long, size: Long, fileSize: Int): Long? {
        if (offset < 0 || size < 0 || offset > fileSize.toLong()) return null
        val end = offset + size
        if (end < offset || end > fileSize.toLong()) return null
        return end
    }

    private fun align4(value: Long): Long = (value + 3L) and -4L

    private fun hasElfMagic(bytes: ByteArray): Boolean =
        bytes[0] == 0x7f.toByte() && bytes[1] == 'E'.code.toByte() &&
            bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte()

    private fun objectTypeName(value: Int): String = when (value) {
        ET_NONE -> "NONE"
        ET_REL -> "REL"
        ET_EXEC -> "EXEC"
        ET_DYN -> "DYN"
        ET_CORE -> "CORE"
        else -> "UNKNOWN($value)"
    }

    private fun machineName(value: Int): String = when (value) {
        EM_386 -> "x86"
        EM_MIPS -> "MIPS"
        EM_ARM -> "ARM"
        EM_X86_64 -> "x86_64"
        EM_AARCH64 -> "AArch64"
        EM_RISCV -> "RISC-V"
        else -> "UNKNOWN($value)"
    }

    private fun sectionTypeName(value: Int): String = when (value) {
        SHT_NULL -> "NULL"
        SHT_PROGBITS -> "PROGBITS"
        SHT_SYMTAB -> "SYMTAB"
        SHT_STRTAB -> "STRTAB"
        SHT_RELA -> "RELA"
        SHT_HASH -> "HASH"
        SHT_DYNAMIC -> "DYNAMIC"
        SHT_NOTE -> "NOTE"
        SHT_NOBITS -> "NOBITS"
        SHT_REL -> "REL"
        SHT_DYNSYM -> "DYNSYM"
        else -> "0x${value.toUInt().toString(16)}"
    }

    private fun programTypeName(value: Int): String = when (value) {
        PT_NULL -> "NULL"
        PT_LOAD -> "LOAD"
        PT_DYNAMIC -> "DYNAMIC"
        PT_INTERP -> "INTERP"
        PT_NOTE -> "NOTE"
        PT_PHDR -> "PHDR"
        PT_TLS -> "TLS"
        PT_GNU_EH_FRAME -> "GNU_EH_FRAME"
        PT_GNU_STACK -> "GNU_STACK"
        PT_GNU_RELRO -> "GNU_RELRO"
        else -> "0x${value.toUInt().toString(16)}"
    }

    private fun programFlags(flags: Long): String = buildString {
        if (flags and PF_R != 0L) append('R')
        if (flags and PF_W != 0L) append('W')
        if (flags and PF_X != 0L) append('X')
        if (isEmpty()) append('-')
    }

    private data class RawSection(
        val index: Int,
        val nameOffset: Int,
        val typeCode: Int,
        val flags: Long,
        val offset: Long,
        val size: Long,
        val link: Int,
        val entrySize: Long,
    )

    private data class NamedSection(
        val index: Int,
        val name: String,
        val typeCode: Int,
        val flags: Long,
        val offset: Long,
        val size: Long,
        val link: Int,
        val entrySize: Long,
    )

    private data class RawSegment(
        val index: Int,
        val typeCode: Int,
        val flags: Long,
        val offset: Long,
        val fileSize: Long,
        val memorySize: Long,
    )

    private data class DynamicInfo(
        val needed: List<String> = emptyList(),
        val soname: String? = null,
        val rpath: String? = null,
        val runpath: String? = null,
        val bindNow: Boolean = false,
    )

    private data class SymbolCollection(
        val imported: List<String>,
        val exported: List<String>,
    )

    private class ElfReader(
        private val data: ByteArray,
        private val byteOrder: ByteOrder,
    ) {
        val size: Int
            get() = data.size

        fun u8(offset: Int): Int {
            requireRange(offset.toLong(), 1)
            return data[offset].toInt() and 0xff
        }

        fun u16(offset: Int): Int = buffer(offset.toLong(), 2).short.toInt() and 0xffff

        fun u32(offset: Int): Long = buffer(offset.toLong(), 4).int.toLong() and 0xffffffffL

        fun s32(offset: Int): Int = buffer(offset.toLong(), 4).int

        fun s64(offset: Int): Long = buffer(offset.toLong(), 8).long

        fun nonNegativeLong(offset: Int): Long {
            val value = s64(offset)
            if (value < 0L) throw AnalysisToolException("ELF 64 位无符号值超出当前解析器范围")
            return value
        }

        fun cString(sectionOffset: Long, sectionSize: Long, relativeOffset: Int): String {
            if (relativeOffset < 0 || relativeOffset.toLong() >= sectionSize) return ""
            val start = sectionOffset + relativeOffset
            val end = safeEnd(sectionOffset, sectionSize) ?: return ""
            if (start < 0 || start >= end || start > Int.MAX_VALUE) return ""
            val builder = StringBuilder()
            var cursor = start.toInt()
            val boundedEnd = min(end, data.size.toLong()).toInt()
            while (cursor < boundedEnd && builder.length < MAX_C_STRING_LENGTH) {
                val value = data[cursor].toInt() and 0xff
                if (value == 0) break
                builder.append(if (value in PRINTABLE_ASCII_RANGE) value.toChar() else '?')
                cursor += 1
            }
            return builder.toString()
        }

        fun ascii(offset: Long, length: Int): String {
            requireRange(offset, length)
            return data.copyOfRange(offset.toInt(), offset.toInt() + length)
                .toString(Charsets.US_ASCII)
        }

        fun hex(offset: Long, length: Int): String {
            requireRange(offset, length)
            return data.copyOfRange(offset.toInt(), offset.toInt() + length)
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }

        private fun buffer(offset: Long, length: Int): ByteBuffer {
            requireRange(offset, length)
            return ByteBuffer.wrap(data, offset.toInt(), length).order(byteOrder)
        }

        private fun requireRange(offset: Long, length: Int) {
            if (offset < 0 || length < 0 || offset > Int.MAX_VALUE || offset + length > data.size.toLong()) {
                throw AnalysisToolException("ELF 读取越界：offset=$offset length=$length size=${data.size}")
            }
        }

        private fun safeEnd(offset: Long, length: Long): Long? {
            if (offset < 0 || length < 0) return null
            val end = offset + length
            return end.takeIf { it >= offset && it <= data.size.toLong() }
        }
    }

    private companion object {
        const val ELF_IDENT_SIZE = 16
        const val ELF32_HEADER_SIZE = 52
        const val ELF64_HEADER_SIZE = 64
        const val ELF32_SECTION_SIZE = 40
        const val ELF64_SECTION_SIZE = 64
        const val ELF32_PROGRAM_SIZE = 32
        const val ELF64_PROGRAM_SIZE = 56
        const val ELF32_DYNAMIC_SIZE = 8
        const val ELF64_DYNAMIC_SIZE = 16
        const val ELF32_SYMBOL_SIZE = 16
        const val ELF64_SYMBOL_SIZE = 24
        const val NOTE_HEADER_SIZE = 12L

        const val ELFCLASS32 = 1
        const val ELFCLASS64 = 2
        const val ELFDATA2LSB = 1
        const val ELFDATA2MSB = 2

        const val ET_NONE = 0
        const val ET_REL = 1
        const val ET_EXEC = 2
        const val ET_DYN = 3
        const val ET_CORE = 4

        const val EM_386 = 3
        const val EM_MIPS = 8
        const val EM_ARM = 40
        const val EM_X86_64 = 62
        const val EM_AARCH64 = 183
        const val EM_RISCV = 243

        const val SHT_NULL = 0
        const val SHT_PROGBITS = 1
        const val SHT_SYMTAB = 2
        const val SHT_STRTAB = 3
        const val SHT_RELA = 4
        const val SHT_HASH = 5
        const val SHT_DYNAMIC = 6
        const val SHT_NOTE = 7
        const val SHT_NOBITS = 8
        const val SHT_REL = 9
        const val SHT_DYNSYM = 11

        const val PT_NULL = 0
        const val PT_LOAD = 1
        const val PT_DYNAMIC = 2
        const val PT_INTERP = 3
        const val PT_NOTE = 4
        const val PT_PHDR = 6
        const val PT_TLS = 7
        const val PT_GNU_EH_FRAME = 0x6474e550
        const val PT_GNU_STACK = 0x6474e551
        const val PT_GNU_RELRO = 0x6474e552

        const val PF_X = 1L
        const val PF_W = 2L
        const val PF_R = 4L

        const val DT_NULL = 0L
        const val DT_NEEDED = 1L
        const val DT_SONAME = 14L
        const val DT_RPATH = 15L
        const val DT_BIND_NOW = 24L
        const val DT_RUNPATH = 29L
        const val DT_FLAGS = 30L
        const val DT_FLAGS_1 = 0x6ffffffbL
        const val DF_BIND_NOW = 0x8L
        const val DF_1_NOW = 0x1L

        const val STB_GLOBAL = 1
        const val STB_WEAK = 2
        const val SHN_UNDEF = 0
        const val PN_XNUM = 0xffff
        const val NT_GNU_BUILD_ID = 3

        const val MAX_SECTION_COUNT = 4_096
        const val MAX_PROGRAM_COUNT = 2_048
        const val MAX_DYNAMIC_ENTRIES = 65_536
        const val MAX_SYMBOL_ENTRIES = 500_000
        const val MAX_COLLECTED_SYMBOLS = 20_000
        const val MAX_VISIBLE_SYMBOLS = 300
        const val MAX_JNI_SYMBOLS = 300
        const val MAX_VISIBLE_SECTIONS = 200
        const val MAX_VISIBLE_SEGMENTS = 100
        const val MAX_NOTES = 1_024
        const val MIN_STRING_LENGTH = 4
        const val MAX_STRING_LENGTH = 512
        const val MAX_C_STRING_LENGTH = 4_096
        const val MAX_COLLECTED_STRINGS = 2_000
        const val MAX_VISIBLE_STRINGS = 300
        val PRINTABLE_ASCII_RANGE = 0x20..0x7e
        val INTERESTING_STRING_MARKERS = listOf(
            "http://", "https://", "jni_onload", "java_", "registernatives", "dlopen", "dlsym",
            "ptrace", "tracerpid", "frida", "xposed", "magisk", "kernelsu", "/system/bin/su",
            "aes", "rsa", "sha", "hmac", "cipher", "encrypt", "decrypt", "openssl", "boringssl",
            "token", "login", "password", "secret", "certificate", "trustmanager", "ssl", "tls",
            "sqlite", "sharedpreferences", "mmkv", "socket", "connect", "send", "recv",
        )
    }
}
