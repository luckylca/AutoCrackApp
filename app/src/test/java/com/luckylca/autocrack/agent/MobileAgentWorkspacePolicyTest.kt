package com.luckylca.autocrack.agent

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileAgentWorkspacePolicyTest {
    @Test
    fun unmarkedExistingConversationUsesLegacySharedWorkspace() {
        val root = Files.createTempDirectory("mobile-agent-workspace-policy-").toFile()
        val sessionWorkspace = root.resolve("agent/existing").apply { mkdirs() }
        val legacyWorkspace = root.resolve("runtime-foundation").apply { mkdirs() }

        val resolved = MobileAgentWorkspacePolicy.resolve(sessionWorkspace, legacyWorkspace)

        assertEquals(legacyWorkspace.canonicalFile, resolved)
    }

    @Test
    fun markedNewConversationUsesIsolatedSessionWorkspace() {
        val root = Files.createTempDirectory("mobile-agent-workspace-policy-").toFile()
        val sessionWorkspace = root.resolve("agent/new")
        val legacyWorkspace = root.resolve("runtime-foundation").apply { mkdirs() }

        val marked = MobileAgentWorkspacePolicy.markIsolated(sessionWorkspace)
        val resolved = MobileAgentWorkspacePolicy.resolve(sessionWorkspace, legacyWorkspace)

        assertTrue(sessionWorkspace.resolve(".autocrack-session-workspace-v1").isFile)
        assertEquals(marked, resolved)
    }
}
