package com.luckylca.autocrack.runtime

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
        assertEquals(0x1688cL, segment.relativeOffset(0x7da9e6688cL))
        assertTrue(segment.executable)
    }

    @Test
    fun decodesHexEncodedStopReplyThreadName() {
        assertEquals(
            "AsyncTask #1",
            HostDebuggerStopReplyDetails.threadName(
                "T05thread:2401;hexname:4173796e635461736b202331;reason:signal;",
            ),
        )
    }

    @Test
    fun decodesAarch64FrameRecordAndValidatesMonotonicFpChain() {
        val bytes = byteArrayOf(
            0x40, 0x10, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x88.toByte(), 0x66, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        )
        val record = Aarch64FrameRecordDecoder.decode(bytes)
        assertEquals(0x1040L, record.previousFramePointer)
        assertEquals(0x6688L, record.savedLinkRegister)

        val segments = HostDebuggerMemoryMapParser.parse(
            "1000-3000 rw-p 00000000 00:00 0 [stack]",
        )
        assertEquals(
            null,
            Aarch64FrameRecordDecoder.validateNextFramePointer(
                currentFramePointer = 0x1020L,
                nextFramePointer = 0x1040L,
                stackPointer = 0x1000L,
                segments = segments,
            ),
        )
        assertEquals(
            "frame_pointer_non_monotonic",
            Aarch64FrameRecordDecoder.validateNextFramePointer(
                currentFramePointer = 0x1040L,
                nextFramePointer = 0x1020L,
                stackPointer = 0x1000L,
                segments = segments,
            ),
        )
    }

    @Test
    fun stackFrameResolverRequiresExecutableMappingAndPreservesModuleOffset() {
        val segments = HostDebuggerMemoryMapParser.parse(
            "4000-5000 r-xp 00001000 00:01 7 /data/app/libfoo.so\n" +
                "7000-8000 rw-p 00000000 00:00 0 [stack]",
        )
        val frame = HostDebuggerStackFrameResolver.resolve(
            index = 1,
            address = 0x4888L,
            framePointer = 0x7100L,
            source = "aarch64_fp_chain",
            segments = segments,
        )!!
        assertEquals("/data/app/libfoo.so", frame.modulePath)
        assertEquals(0x1888L, frame.moduleOffset)
        assertEquals(null, HostDebuggerStackFrameResolver.resolve(2, 0x7100L, 0x7200L, "bad", segments))
    }

    @Test
    fun decodesImportantAarch64ControlFlowWithoutExternalToolpack() {
        assertEquals("nop", Aarch64InstructionContextDecoder.decode(0xd503201fL, 0x1000L))
        assertEquals("ret", Aarch64InstructionContextDecoder.decode(0xd65f03c0L, 0x1000L))
        assertEquals("b 0x1008", Aarch64InstructionContextDecoder.decode(0x14000002L, 0x1000L))
        assertEquals("bl 0x1008", Aarch64InstructionContextDecoder.decode(0x94000002L, 0x1000L))
    }
}
