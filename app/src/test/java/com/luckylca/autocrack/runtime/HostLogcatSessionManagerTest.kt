package com.luckylca.autocrack.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostLogcatSessionManagerTest {
    @Test
    fun commandFactory_buildsFixedPidScopedLogcatCommand() {
        val command = HostLogcatCommandFactory.build("/system/bin/su", 21743)

        assertEquals("/system/bin/su", command[0])
        assertEquals("-c", command[1])
        assertEquals("exec logcat --pid=21743 -v threadtime", command[2])
        assertFalse(command[2].contains("kill"))
        assertFalse(command[2].contains("ptrace"))
        assertFalse(command[2].contains("/proc/21743/mem"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun commandFactory_rejectsNonPositivePid() {
        HostLogcatCommandFactory.build("/system/bin/su", 0)
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
