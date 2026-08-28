package com.luckylca.autocrack.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostDebuggerControlGateTest {
    @Test
    fun allowsOnlyVerifiedTargetlessListenerBeforeTypedAttach() {
        assertTrue(
            HostDebuggerControlGate.canAttemptConnection(
                running = true,
                helperVerified = true,
                serverReadyForClient = true,
                tracerPidCurrent = 0,
                failure = null,
            ),
        )
    }

    @Test
    fun rejectsUnreadyAlreadyTracedOrBrokenStates() {
        assertFalse(
            HostDebuggerControlGate.canAttemptConnection(
                running = true,
                helperVerified = true,
                serverReadyForClient = false,
                tracerPidCurrent = 0,
                failure = null,
            ),
        )
        assertFalse(
            HostDebuggerControlGate.canAttemptConnection(
                running = true,
                helperVerified = false,
                serverReadyForClient = true,
                tracerPidCurrent = 0,
                failure = null,
            ),
        )
        assertFalse(
            HostDebuggerControlGate.canAttemptConnection(
                running = true,
                helperVerified = true,
                serverReadyForClient = true,
                tracerPidCurrent = 8123,
                failure = null,
            ),
        )
        assertFalse(
            HostDebuggerControlGate.canAttemptConnection(
                running = false,
                helperVerified = true,
                serverReadyForClient = true,
                tracerPidCurrent = 0,
                failure = null,
            ),
        )
        assertFalse(
            HostDebuggerControlGate.canAttemptConnection(
                running = true,
                helperVerified = true,
                serverReadyForClient = true,
                tracerPidCurrent = 0,
                failure = "server failed",
            ),
        )
    }
}
