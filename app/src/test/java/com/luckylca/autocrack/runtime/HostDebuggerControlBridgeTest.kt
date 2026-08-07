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
    fun controlBridgeExposesNoWriteOrBreakpointMethods() {
        val methodNames = HostDebuggerControlBridge::class.java.methods.map { method -> method.name }.toSet()
        assertEquals(false, "writeMemory" in methodNames)
        assertEquals(false, "writeRegister" in methodNames)
        assertEquals(false, "insertBreakpoint" in methodNames)
        assertEquals(false, "removeBreakpoint" in methodNames)
        assertEquals(false, "sendRawPacket" in methodNames)
    }
}
