package com.luckylca.autocrack.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostLogcatSessionManagerTest {
    @Test
    fun commandFactory_buildsFixedPidScopedLogcatCommandWithIdentityGate() {
        val command = HostLogcatCommandFactory.build(
            suPath = "/system/bin/su",
            packageName = "com.example.app",
            pid = 21743,
        )

        assertEquals("/system/bin/su", command[0])
        assertEquals("-c", command[1])
        val shell = command[2]
        assertTrue(shell.contains("expected_package='com.example.app'"))
        assertTrue(shell.contains("proc=/proc/21743"))
        assertTrue(shell.contains("IDENTITY_MISMATCH pid=21743"))
        assertTrue(shell.contains("exec logcat --pid=21743 -v threadtime"))
        assertFalse(shell.contains("kill"))
        assertFalse(shell.contains("ptrace"))
        assertFalse(shell.contains("/proc/21743/mem"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun commandFactory_rejectsNonPositivePid() {
        HostLogcatCommandFactory.build(
            suPath = "/system/bin/su",
            packageName = "com.example.app",
            pid = 0,
        )
    }

    @Test
    fun identityMatcher_acceptsExactPackageAndSecondaryProcess() {
        assertTrue(
            HostLogcatIdentityMatcher.matches(
                "com.example.app",
                "pid=42\ncmdline=com.example.app\nTracerPid:\t0",
            ),
        )
        assertTrue(
            HostLogcatIdentityMatcher.matches(
                "com.example.app",
                "pid=43\ncmdline=com.example.app:worker --flag\nTracerPid:\t0",
            ),
        )
    }

    @Test
    fun identityMatcher_rejectsPrefixSpoofAndUnrelatedProcess() {
        assertFalse(
            HostLogcatIdentityMatcher.matches(
                "com.example.app",
                "pid=44\ncmdline=com.example.application\n",
            ),
        )
        assertFalse(
            HostLogcatIdentityMatcher.matches(
                "com.example.app",
                "pid=45\ncmdline=com.other.app\n",
            ),
        )
    }
}
