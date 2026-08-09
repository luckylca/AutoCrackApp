package com.luckylca.autocrack.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostDebuggerControlGateTest {
    @Test
    fun allowsRunningServerWaitingForClient() {
        assertTrue(
            HostDebuggerControlGate.canAttemptConnection(
                running = true,
                attachedObserved = false,
                tracerPidCurrent = 0,
                failure = null,
            ),
        )
    }

    @Test
    fun allowsAlreadyConfirmedSession() {
        assertTrue(
            HostDebuggerControlGate.canAttemptConnection(
                running = true,
                attachedObserved = true,
                tracerPidCurrent = 8123,
                failure = null,
            ),
        )
    }

    @Test
    fun rejectsUnconfirmedOrBrokenStates() {
        assertFalse(
            HostDebuggerControlGate.canAttemptConnection(
                running = true,
                attachedObserved = false,
                tracerPidCurrent = 8123,
                failure = null,
            ),
        )
        assertFalse(
            HostDebuggerControlGate.canAttemptConnection(
                running = true,
                attachedObserved = false,
                tracerPidCurrent = null,
                failure = null,
            ),
        )
        assertFalse(
            HostDebuggerControlGate.canAttemptConnection(
                running = false,
                attachedObserved = false,
                tracerPidCurrent = 0,
                failure = null,
            ),
        )
        assertFalse(
            HostDebuggerControlGate.canAttemptConnection(
                running = true,
                attachedObserved = false,
                tracerPidCurrent = 0,
                failure = "server failed",
            ),
        )
    }
}
