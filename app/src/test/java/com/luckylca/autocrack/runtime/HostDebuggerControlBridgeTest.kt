package com.luckylca.autocrack.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HostDebuggerControlBridgeTest {
    @Test
    fun exactControlAuthorizationIncludesPackageAndPid() {
        assertEquals(
            "CONTROL com.example.authorized 1234",
            HostDebuggerControlAuthorization.expected("com.example.authorized", 1234),
        )
        HostDebuggerControlAuthorization.requireAuthorized(
            "com.example.authorized",
            1234,
            "CONTROL com.example.authorized 1234",
        )
    }

    @Test
    fun rejectsControlAuthorizationForDifferentPid() {
        assertThrows(IllegalArgumentException::class.java) {
            HostDebuggerControlAuthorization.requireAuthorized(
                "com.example.authorized",
                1234,
                "CONTROL com.example.authorized 5678",
            )
        }
    }

    @Test
    fun breakpointHitAccountingIncrementsOnlyManualMatchingTrustedBreakpoint() {
        val context = HostDebuggerCodeContextSnapshot(
            threadId = "393",
            threadName = "main",
            pc = 0x4000L,
            lr = null,
            sp = null,
            framePointer = null,
            stack = HostDebuggerStackSnapshot(emptyList(), "test", partial = true),
            modulePath = "/data/app/libfoo.so",
            moduleBase = 0x3000L,
            moduleOffset = 0x1000L,
            segmentStart = 0x4000L,
            segmentEndExclusive = 0x5000L,
            segmentPermissions = "r-xp",
            segmentFileOffset = 0x1000L,
            memoryStartAddress = 0x4000L,
            memoryHex = "1f2003d5",
            instructions = emptyList(),
        )
        val updated = HostDebuggerBreakpointHitAccounting.applyTrustedStop(
            breakpoints = listOf(
                HostDebuggerBreakpointSnapshot(0x4000L, 4),
                HostDebuggerBreakpointSnapshot(0x4000L, 4, autoManaged = true),
                HostDebuggerBreakpointSnapshot(0x4010L, 4),
            ),
            stopReply = "T05thread:393;reason:breakpoint;",
            context = context,
            timestampEpochMillis = 123L,
        )
        assertEquals(1, updated[0].hitCount)
        assertEquals("393", updated[0].lastHitThreadId)
        assertEquals(123L, updated[0].lastHitAtEpochMillis)
        assertEquals(0, updated[1].hitCount)
        assertEquals(0, updated[2].hitCount)
    }

    @Test
    fun controlBridgeExposesOnlyTypedHardwareBreakpointMethods() {
        val methodNames = HostDebuggerControlBridge::class.java.methods.map { method -> method.name }.toSet()
        assertEquals(false, "writeMemory" in methodNames)
        assertEquals(false, "writeRegister" in methodNames)
        assertEquals(false, "insertBreakpoint" in methodNames)
        assertEquals(false, "removeBreakpoint" in methodNames)
        assertEquals(false, "sendRawPacket" in methodNames)
        assertEquals(true, "setHardwareExecutionBreakpoint" in methodNames)
        assertEquals(true, "removeHardwareExecutionBreakpoint" in methodNames)
    }
}
