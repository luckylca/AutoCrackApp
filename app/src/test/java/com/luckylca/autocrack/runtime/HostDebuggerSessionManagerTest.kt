package com.luckylca.autocrack.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostDebuggerSessionManagerTest {
    @Test
    fun authorization_requiresExactTargetPhrase() {
        val expected = HostDebuggerAuthorization.expected("com.example.target", 1234)
        assertEquals("ATTACH com.example.target 1234", expected)
        HostDebuggerAuthorization.requireAuthorized("com.example.target", 1234, expected)
    }

    @Test(expected = IllegalArgumentException::class)
    fun authorization_rejectsGenericConfirmation() {
        HostDebuggerAuthorization.requireAuthorized("com.example.target", 1234, "ATTACH")
    }

    @Test
    fun attachCommand_revalidatesIdentityAndTracerThenBindsLoopbackOnly() {
        val command = HostDebuggerCommandFactory.buildAttach(
            suPath = "/system/bin/su",
            binaryPath = "/data/user/0/com.luckylca.autocrack/files/runtime/rootfs/current/opt/autocrack/toolpacks/packs/android-lldb-server/v/bin/lldb-server-android",
            packageName = "com.example.target",
            pid = 4321,
            port = 5039,
            helperPidFile = "/data/user/0/com.luckylca.autocrack/files/runtime/sessions/debugger/test.helper.pid",
        )

        assertEquals("/system/bin/su", command[0])
        assertEquals("-c", command[1])
        val shell = command[2]
        assertTrue(shell.contains("/proc/$" + "target_pid"))
        assertTrue(shell.contains("DEBUG_TARGET_IDENTITY_MISMATCH"))
        assertTrue(shell.contains("TracerPid:"))
        assertTrue(shell.contains("127.0.0.1:5039"))
        assertTrue(shell.contains("gdbserver"))
        assertTrue(shell.contains("--attach"))
        assertFalse(shell.contains("0.0.0.0"))
        assertFalse(shell.contains("/proc/4321/mem"))
        assertFalse(shell.contains("kill -TERM 4321"))
    }

    @Test
    fun stopCommand_signalsOnlyIdentityCheckedHelper() {
        val binary = "/data/user/0/com.luckylca.autocrack/files/runtime/rootfs/current/opt/autocrack/toolpacks/packs/android-lldb-server/v/bin/lldb-server-android"
        val command = HostDebuggerCommandFactory.buildStopHelper(
            suPath = "/system/bin/su",
            binaryPath = binary,
            helperPid = 9001,
        )
        val shell = command[2]

        assertTrue(shell.contains("helper_pid=9001"))
        assertTrue(shell.contains("DEBUG_HELPER_IDENTITY_MISMATCH"))
        assertTrue(shell.contains("kill -TERM \"$" + "helper_pid\""))
        assertFalse(shell.contains("kill -KILL"))
        assertFalse(shell.contains("target_pid"))
    }

    @Test
    fun targetStatusParser_readsTracerAndState() {
        val status = HostDebuggerTargetStatusParser.parse(
            "pid=44\nState:\tt (tracing stop)\nTracerPid:\t8123\nThreads:\t7",
        )
        requireNotNull(status)
        assertEquals(8123, status.tracerPid)
        assertEquals("t (tracing stop)", status.state)
    }

    @Test
    fun targetStatusParser_requiresTracerPid() {
        assertTrue(HostDebuggerTargetStatusParser.parse("State:\tS (sleeping)") == null)
    }
}
