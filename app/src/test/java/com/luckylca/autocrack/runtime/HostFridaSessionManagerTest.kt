package com.luckylca.autocrack.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HostFridaSessionManagerTest {
    @Test
    fun authorizationIsExactAndTargetBound() {
        assertEquals("FRIDA com.example.myapplication 1234", HostFridaAuthorization.expected("com.example.myapplication", 1234))
        HostFridaAuthorization.requireAuthorized(
            "com.example.myapplication",
            1234,
            "FRIDA com.example.myapplication 1234",
        )
        assertThrows(IllegalArgumentException::class.java) {
            HostFridaAuthorization.requireAuthorized(
                "com.example.myapplication",
                1234,
                "FRIDA com.other.app 1234",
            )
        }
    }

    @Test
    fun serverStartIsLoopbackOnlyAndRevalidatesTarget() {
        val command = HostFridaCommandFactory.buildStartServer(
            suPath = "/system/bin/su",
            binaryPath = "/data/local/frida-server-android",
            expectedBinarySha256 = "55ef78c3f3e7a55122ca7e0051e2a356d0ff1d9744d84c1660291f90400588e7",
            packageName = "com.example.myapplication",
            pid = 1234,
            port = HostFridaSessionManager.DEFAULT_PORT,
            helperPidFile = "/data/local/frida.pid",
        )
        assertEquals("/system/bin/su", command.first())
        val shell = command.last()
        assertTrue(shell.contains("127.0.0.1:27042"))
        assertTrue(shell.contains("FRIDA_TARGET_IDENTITY_MISMATCH"))
        assertTrue(shell.contains("TracerPid:"))
        assertTrue(shell.contains("[ -x \"${'$'}binary\" ]"))
        assertTrue(shell.contains("FRIDA_SERVER_NOT_EXECUTABLE"))
        assertTrue(shell.contains("FRIDA_SERVER_SHA256_MISMATCH"))
        assertTrue(shell.contains("55ef78c3f3e7a55122ca7e0051e2a356d0ff1d9744d84c1660291f90400588e7"))
        assertFalse(shell.contains("0.0.0.0"))
    }

    @Test
    fun serverStartSupportsInternalAlternateLoopbackPort() {
        val command = HostFridaCommandFactory.buildStartServer(
            suPath = "/system/bin/su",
            binaryPath = "/data/local/frida-server-android",
            expectedBinarySha256 = "55ef78c3f3e7a55122ca7e0051e2a356d0ff1d9744d84c1660291f90400588e7",
            packageName = "com.example.myapplication",
            pid = 1234,
            port = 32123,
            helperPidFile = "/data/local/frida.pid",
        )
        val shell = command.last()
        assertTrue(shell.contains("127.0.0.1:32123"))
        assertTrue(shell.contains("printf '%s %s"))
        assertFalse(shell.contains("0.0.0.0"))
    }

    @Test
    fun clientCommandCarriesInternalPortAndRejectsPrivilegedPort() {
        val command = HostFridaCommandFactory.buildClientCommand(
            1234,
            HostFridaClientOperation.Ping,
            port = 32123,
        )
        assertTrue(command.contains("--port"))
        assertTrue(command.contains("32123"))
        assertThrows(IllegalArgumentException::class.java) {
            HostFridaCommandFactory.buildClientCommand(1234, HostFridaClientOperation.Ping, port = 80)
        }
    }

    @Test
    fun helperProbeRequiresExactBinaryAndLoopbackListenerOwnership() {
        val shell = HostFridaCommandFactory.buildProbeHelper(
            suPath = "/system/bin/su",
            binaryPath = "/data/local/frida-server-android",
            helperPid = 4321,
            port = HostFridaSessionManager.DEFAULT_PORT,
        ).last()
        assertTrue(shell.contains("FRIDA_HELPER_COMMAND_MISMATCH"))
        assertTrue(shell.contains("/proc/net/tcp"))
        assertTrue(shell.contains("ls -l \"${'$'}proc\"/fd"))
        assertTrue(shell.contains("socket:["))
        assertFalse(shell.contains("readlink \"${'$'}fd\""))
        assertTrue(shell.contains("listener_ready"))
    }

    @Test
    fun helperStopRevalidatesExactCommandBeforeSignal() {
        val shell = HostFridaCommandFactory.buildStopHelper(
            suPath = "/system/bin/su",
            binaryPath = "/data/local/frida-server-android",
            helperPid = 4321,
            port = HostFridaSessionManager.DEFAULT_PORT,
        ).last()
        assertTrue(shell.contains("FRIDA_HELPER_IDENTITY_MISMATCH"))
        assertTrue(shell.contains("FRIDA_HELPER_COMMAND_MISMATCH"))
        assertTrue(shell.contains("127.0.0.1:27042"))
        assertTrue(shell.contains("kill -TERM"))
    }

    @Test
    fun nativeTraceCommandIsTypedAndBounded() {
        val command = HostFridaCommandFactory.buildClientCommand(
            1234,
            HostFridaClientOperation.NativeTrace(
                module = "libfoo.so",
                offset = "0x1234",
                durationMillis = 99_999,
                maxEvents = 999,
            ),
        )
        assertTrue(command.contains("frida-autocrack-client"))
        assertTrue(command.contains("native-trace"))
        assertTrue(command.contains("5000"))
        assertTrue(command.contains("128"))
        assertFalse(command.contains("eval"))
        assertFalse(command.contains("Memory.write"))
    }

    @Test
    fun networkStackCommandIsTypedAndBounded() {
        val command = HostFridaCommandFactory.buildClientCommand(
            1234,
            HostFridaClientOperation.NetDetectStack(maxCount = 999),
        )
        assertTrue(command.contains("net-stack"))
        assertTrue(command.contains("128"))
        assertFalse(command.contains("eval"))
    }

    @Test
    fun networkHintsCommandIsTypedAndBounded() {
        val command = HostFridaCommandFactory.buildClientCommand(
            1234,
            HostFridaClientOperation.NetworkHints(maxCount = 999),
        )
        assertTrue(command.contains("net-hints"))
        assertTrue(command.contains("128"))
        assertFalse(command.contains("eval"))
        assertFalse(command.contains("bypass"))
    }

    @Test
    fun tlsTraceCommandIsTimeEventAndPreviewBounded() {
        val command = HostFridaCommandFactory.buildClientCommand(
            1234,
            HostFridaClientOperation.TlsTrace(
                durationMillis = 99_999,
                maxEvents = 999,
                maxBytesPerEvent = 99_999,
            ),
        )
        assertTrue(command.contains("tls-trace"))
        assertTrue(command.contains("5000"))
        assertTrue(command.contains("128"))
        assertTrue(command.contains("1024"))
        assertFalse(command.contains("pinning"))
        assertFalse(command.contains("Memory.write"))
        assertFalse(command.contains("Interceptor.replace"))
    }

    @Test
    fun nativeTraceRejectsNonHexOffset() {
        assertThrows(IllegalArgumentException::class.java) {
            HostFridaCommandFactory.buildClientCommand(
                1234,
                HostFridaClientOperation.NativeTrace("libfoo.so", "1234"),
            )
        }
    }
}
