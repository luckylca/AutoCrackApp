package com.luckylca.autocrack.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileAgentPromptBuilderTest {
    @Test
    fun promptUsesMinimalPiStyleHarnessStructure() {
        val prompt = MobileAgentPromptBuilder.build(
            MobileAgentRuntimeSession(
                tools = AgentToolSession(emptyList()),
                installedToolpacks = emptyList(),
                workspacePath = "/workspace",
                cancelAllCommands = { 0 },
            ),
        )

        assertTrue(prompt.contains("expert technical assistant operating inside AutoCrack Mobile Agent"))
        assertTrue(prompt.contains("Available tools:"))
        assertTrue(prompt.contains("Guidelines:"))
        assertTrue(prompt.contains("Current working directory: /workspace"))
        assertTrue(prompt.contains("Use android-shell from Bash"))
        assertTrue(prompt.contains("prefer their standard CLI and language APIs"))
        assertTrue(prompt.contains("exec_bash is the main capability"))
        assertTrue(prompt.contains("ordinary shell commands on PATH, not separate model tools"))
        assertTrue(prompt.contains("inspect its --help"))
        assertTrue(prompt.contains("SKILL.md"))
        assertTrue(prompt.contains("Prefer machine-readable --json output"))
        assertTrue(prompt.contains("runtime-inspect doctor --package <target> --json"))
        assertTrue(prompt.contains("obj_* handles and asynchronous tokens"))
        assertFalse(prompt.contains("reverse-engineering"))
        assertFalse(prompt.contains("packages, and runtime state"))
        assertFalse(prompt.contains("Ask the user only when progress genuinely requires"))
    }

    @Test
    fun promptAppendsPersistentUserInstructions() {
        val prompt = MobileAgentPromptBuilder.build(
            MobileAgentRuntimeSession(
                tools = AgentToolSession(emptyList()),
                installedToolpacks = emptyList(),
                workspacePath = "/workspace",
                cancelAllCommands = { 0 },
            ),
            MobileAgentPreferences(customSystemPrompt = "Always inspect package metadata first."),
        )

        assertTrue(prompt.contains("Additional persistent user instructions:"))
        assertTrue(prompt.contains("Always inspect package metadata first."))
    }
}
