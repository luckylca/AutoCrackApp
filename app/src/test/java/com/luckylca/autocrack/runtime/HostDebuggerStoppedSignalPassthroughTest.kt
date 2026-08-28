package com.luckylca.autocrack.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class HostDebuggerStoppedSignalPassthroughTest {
    @Test
    fun parsesAndBuildsExactSigsegvPassThrough() {
        val stop = "T0bthread:5b6a;reason:signal;"
        assertEquals(11, GdbRemoteStoppedSignalPassthrough.signalNumber(stop))
        assertEquals(
            "vCont;C0b:5b6a;c",
            GdbRemoteStoppedSignalPassthrough.packetFromStopReply(stop),
        )
    }

    @Test
    fun explicitSignalReasonSigtrapIsThreadScoped() {
        val stop = "T05thread:64b;reason:signal;"
        assertEquals(5, GdbRemoteStoppedSignalPassthrough.signalNumber(stop))
        assertEquals(
            "vCont;C05:64b;c",
            GdbRemoteStoppedSignalPassthrough.packetFromStopReply(stop),
        )
    }

    @Test
    fun exitReplyHasNoStoppedSignal() {
        assertNull(GdbRemoteStoppedSignalPassthrough.signalNumber("W00"))
    }
}
