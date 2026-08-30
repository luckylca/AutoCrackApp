package com.luckylca.autocrack.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostDebuggerRecoveryBridgeTest {
    private val binary = "/data/user/0/com.luckylca.autocrack/files/runtime/rootfs/current/opt/autocrack/toolpacks/packs/android-lldb-server/v/host-bin/lldb-server-android"

    @Test
    fun recoveryAuthorization_isBoundToPackageTargetAndTracer() {
        val expected = HostDebuggerRecoveryAuthorization.expected("com.example.target", 15153, 10672)
        assertEquals("RECOVER com.example.target 15153 10672", expected)
        HostDebuggerRecoveryAuthorization.requireAuthorized(
            packageName = "com.example.target",
            pid = 15153,
            tracerPid = 10672,
            supplied = expected,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun recoveryAuthorization_rejectsStaleTracer() {
        HostDebuggerRecoveryAuthorization.requireAuthorized(
            packageName = "com.example.target",
            pid = 15153,
            tracerPid = 10672,
            supplied = "RECOVER com.example.target 15153 9999",
        )
    }

    @Test
    fun inspectCommand_requiresTargetTracerAndExactTrustedHelperIdentity() {
        val command = HostDebuggerRecoveryCommandFactory.buildInspect(
            suPath = "/system/bin/su",
            binaryPath = binary,
            packageName = "com.example.target",
            pid = 15153,
        )
        val shell = command[2]

        assertTrue(shell.contains("TracerPid:"))
        assertTrue(shell.contains("RECOVERY_TARGET_IDENTITY_MISMATCH"))
        assertTrue(shell.contains("RECOVERY_HELPER_IDENTITY_MISMATCH"))
        assertTrue(shell.contains("RECOVERY_HELPER_COMMAND_MISMATCH"))
        assertTrue(shell.contains("gdbserver 127.0.0.1:"))
        assertTrue(shell.contains("--attach $" + "target_pid"))
        assertFalse(shell.contains("kill -TERM"))
        assertFalse(shell.contains("kill -KILL"))
    }

    @Test
    fun recoveryDetach_revalidatesTracerBeforeSignalingOnlyHelper() {
        val command = HostDebuggerRecoveryCommandFactory.buildDetach(
            suPath = "/system/bin/su",
            binaryPath = binary,
            packageName = "com.example.target",
            pid = 15153,
            expectedTracerPid = 10672,
        )
        val shell = command[2]

        assertTrue(shell.contains("RECOVERY_TRACER_CHANGED"))
        assertTrue(shell.contains("[ \"$" + "tracer\" = \"10672\" ]"))
        assertTrue(shell.contains("kill -TERM \"$" + "tracer\""))
        assertFalse(shell.contains("kill -TERM 15153"))
        assertFalse(shell.contains("kill -KILL"))
        assertTrue(shell.contains("target_pid=15153"))
    }
}
