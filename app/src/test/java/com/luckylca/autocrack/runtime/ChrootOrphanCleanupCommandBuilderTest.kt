package com.luckylca.autocrack.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChrootOrphanCleanupCommandBuilderTest {
    @Test
    fun cleanupMatchesOnlyExactGeneratedEnvironmentToken() {
        val token = "123e4567-e89b-12d3-a456-426614174000"
        val script = ChrootOrphanCleanupCommandBuilder.build(token)

        assertTrue(script.contains("AUTOC_CHROOT_REQUEST_TOKEN=$token"))
        assertTrue(script.contains("/proc/[0-9]*/environ"))
        assertTrue(script.contains("grep -a -l -F -- \"${'$'}MARKER\""))
        assertFalse(script.contains("tr '\\000' '\\n'"))
        assertTrue(script.contains("kill -TERM"))
        assertTrue(script.contains("kill -KILL"))
        assertFalse(script.contains("pkill"))
    }

    @Test
    fun cleanupRejectsUntrustedTokenText() {
        assertThrows(IllegalArgumentException::class.java) {
            ChrootOrphanCleanupCommandBuilder.build("bad;kill -9 1")
        }
    }

    @Test
    fun staleAgentCleanupTargetsOnlyRawBashAgentProcesses() {
        val script = ChrootStaleAgentCleanupCommandBuilder.build()

        assertTrue(script.contains("AUTOC_AGENT_MODE=raw_bash"))
        assertTrue(script.contains("/proc/[0-9]*/environ"))
        assertTrue(script.contains("kill -TERM"))
        assertTrue(script.contains("kill -KILL"))
        assertFalse(script.contains("pkill"))
    }

    @Test
    fun agentSessionCleanupMatchesOnlyExactSessionMarker() {
        val sessionId = "123e4567-e89b-12d3-a456-426614174000"
        val script = ChrootAgentSessionCleanupCommandBuilder.build(sessionId)

        assertTrue(script.contains("AUTOC_AGENT_SESSION_ID=$sessionId"))
        assertTrue(script.contains("/proc/[0-9]*/environ"))
        assertTrue(script.contains("kill -TERM"))
        assertTrue(script.contains("kill -KILL"))
        assertFalse(script.contains("pkill"))
    }

    @Test
    fun agentSessionCleanupRejectsNonUuidSessionText() {
        assertThrows(IllegalArgumentException::class.java) {
            ChrootAgentSessionCleanupCommandBuilder.build("session;kill -9 1")
        }
    }
}
