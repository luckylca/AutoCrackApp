package com.luckylca.autocrack.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostDebuggerPostAcceptGateTest {
    @Test
    fun allowsVerifiedHelperAfterListenerWasConsumedByAcceptedClient() {
        assertTrue(
            HostDebuggerPostAcceptGate.canSendTypedAttach(
                running = true,
                helperVerified = true,
                tracerPidCurrent = 0,
                failure = null,
            ),
        )
    }

    @Test
    fun rejectsBrokenHelperOrTargetState() {
        assertFalse(
            HostDebuggerPostAcceptGate.canSendTypedAttach(
                running = false,
                helperVerified = true,
                tracerPidCurrent = 0,
                failure = null,
            ),
        )
        assertFalse(
            HostDebuggerPostAcceptGate.canSendTypedAttach(
                running = true,
                helperVerified = false,
                tracerPidCurrent = 0,
                failure = null,
            ),
        )
        assertFalse(
            HostDebuggerPostAcceptGate.canSendTypedAttach(
                running = true,
                helperVerified = true,
                tracerPidCurrent = 9123,
                failure = null,
            ),
        )
        assertFalse(
            HostDebuggerPostAcceptGate.canSendTypedAttach(
                running = true,
                helperVerified = true,
                tracerPidCurrent = 0,
                failure = "helper identity changed",
            ),
        )
    }
}
