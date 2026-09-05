package com.luckylca.autocrack.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChrootOrphanCleanupCommandBuilderTest {
    private fun assertBoundedSingleScan(script: String) {
        assertTrue(script.contains("ps -A -o PID=,PPID=,UID="))
        assertTrue(script.contains("PROCESS_ROOT=${'$'}(readlink \"/proc/${'$'}PID/root\""))
        assertTrue(script.contains("[ \"${'$'}PROCESS_ROOT\" = \"${'$'}ROOTFS\" ] || continue"))
        assertTrue(script.contains("timeout -k 0.02 0.05 grep -a -q -F -- \"${'$'}MARKER\""))
        assertTrue(script.contains("still_alive"))
        assertFalse(script.contains("grep -a -l -F --"))
        assertFalse(script.contains("/proc/[0-9]*/environ"))
        assertFalse(script.contains("pkill"))
    }

    @Test
    fun cleanupMatchesOnlyExactGeneratedEnvironmentToken() {
        val token = "123e4567-e89b-12d3-a456-426614174000"
        val script = ChrootOrphanCleanupCommandBuilder.build(token, "/managed/rootfs/current")

        assertTrue(script.contains("AUTOC_CHROOT_REQUEST_TOKEN=$token"))
        assertBoundedSingleScan(script)
        assertTrue(script.contains("kill -TERM"))
        assertTrue(script.contains("kill -KILL"))
    }

    @Test
    fun cleanupRejectsUntrustedTokenText() {
        assertThrows(IllegalArgumentException::class.java) {
            ChrootOrphanCleanupCommandBuilder.build("bad;kill -9 1", "/managed/rootfs/current")
        }
    }

    @Test
    fun staleAgentCleanupTargetsOnlyRawBashAgentProcesses() {
        val script = ChrootStaleAgentCleanupCommandBuilder.build("/managed/rootfs/current")

        assertTrue(script.contains("AUTOC_AGENT_MODE=raw_bash"))
        assertBoundedSingleScan(script)
        assertTrue(script.contains("kill -TERM"))
        assertTrue(script.contains("kill -KILL"))
    }

    @Test
    fun agentSessionCleanupMatchesOnlyExactSessionMarker() {
        val sessionId = "123e4567-e89b-12d3-a456-426614174000"
        val script = ChrootAgentSessionCleanupCommandBuilder.build(sessionId, "/managed/rootfs/current")

        assertTrue(script.contains("AUTOC_AGENT_SESSION_ID=$sessionId"))
        assertBoundedSingleScan(script)
        assertTrue(script.contains("kill -TERM"))
        assertTrue(script.contains("kill -KILL"))
    }

    @Test
    fun agentSessionCleanupRejectsNonUuidSessionText() {
        assertThrows(IllegalArgumentException::class.java) {
            ChrootAgentSessionCleanupCommandBuilder.build("session;kill -9 1", "/managed/rootfs/current")
        }
    }
}
