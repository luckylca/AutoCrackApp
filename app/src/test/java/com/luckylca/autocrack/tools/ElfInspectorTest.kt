package com.luckylca.autocrack.tools

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ElfInspectorTest {
    private val inspector = ElfInspector()

    @Test
    fun rejectsNonElfInput() {
        val exception = assertThrows(AnalysisToolException::class.java) {
            inspector.inspect(ByteArray(64), "invalid.so")
        }
        assertTrue(exception.message.orEmpty().contains("ELF"))
    }

    @Test
    fun parsesMinimalLittleEndianElf64Header() {
        val bytes = ByteArray(64)
        bytes[0] = 0x7f
        bytes[1] = 'E'.code.toByte()
        bytes[2] = 'L'.code.toByte()
        bytes[3] = 'F'.code.toByte()
        bytes[4] = 2
        bytes[5] = 1
        bytes[6] = 1
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(16, 3)
            putShort(18, 183)
            putInt(20, 1)
            putLong(24, 0)
            putLong(32, 0)
            putLong(40, 0)
            putShort(52, 64)
            putShort(54, 56)
            putShort(56, 0)
            putShort(58, 64)
            putShort(60, 0)
            putShort(62, 0)
        }

        val report = inspector.inspect(bytes, "libminimal.so")

        assertEquals("ELF64", report.elfClass)
        assertEquals("Little Endian", report.byteOrder)
        assertEquals("DYN", report.objectType)
        assertEquals("AArch64", report.machine)
        assertTrue(report.hardening.positionIndependent)
        assertEquals("UNKNOWN", report.hardening.nx)
    }
}
