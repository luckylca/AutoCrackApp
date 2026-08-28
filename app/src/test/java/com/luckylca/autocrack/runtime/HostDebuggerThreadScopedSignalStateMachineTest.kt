package com.luckylca.autocrack.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class HostDebuggerThreadScopedSignalStateMachineTest {
    @Test
    fun scopesSigsegvToOnlyTheStoppedThread() {
        val stop = "T0bthread:7a23;reason:signal;"
        assertTrue(GdbRemoteStoppedSignalPassthrough.isAutoPassableTargetSignal(stop))
        assertEquals(
            "vCont;C0b:7a23;c",
            GdbRemoteStoppedSignalPassthrough.packetFromStopReply(stop),
        )
    }

    @Test
    fun explicitSignalReasonSigtrapIsTargetSignalButGenericTrapIsReserved() {
        val targetTrap = "T05thread:7a23;reason:signal;"
        assertTrue(GdbRemoteStoppedSignalPassthrough.isAutoPassableTargetSignal(targetTrap))
        assertEquals(
            "vCont;C05:7a23;c",
            GdbRemoteStoppedSignalPassthrough.packetFromStopReply(targetTrap),
        )

        val debuggerTrap = "T05thread:7a23;reason:breakpoint;"
        assertFalse(GdbRemoteStoppedSignalPassthrough.isAutoPassableTargetSignal(debuggerTrap))
        assertThrows(IllegalArgumentException::class.java) {
            GdbRemoteStoppedSignalPassthrough.packetFromStopReply(debuggerTrap)
        }
    }

    @Test
    fun multiprocessThreadIdRemainsScoped() {
        val stop = "T0bthread:p6901.7a23;reason:signal;"
        assertEquals(
            "vCont;C0b:p6901.7a23;c",
            GdbRemoteStoppedSignalPassthrough.packetFromStopReply(stop),
        )
    }
}
