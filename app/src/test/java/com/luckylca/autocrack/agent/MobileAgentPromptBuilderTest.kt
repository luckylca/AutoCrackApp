package com.luckylca.autocrack.agent

import com.luckylca.autocrack.runtime.InstalledToolpack
import com.luckylca.autocrack.runtime.ToolpackCommand
import com.luckylca.autocrack.runtime.ToolpackPackageManifest
import com.luckylca.autocrack.runtime.ToolpackSelfTest
import com.luckylca.autocrack.runtime.ToolpackSourceArtifact

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
        assertTrue(prompt.contains("command -v, --help"))
        assertTrue(prompt.contains("SKILL.md"))
        assertTrue(prompt.contains("Prefer machine-readable --json output"))
        assertTrue(prompt.contains("runtime-inspect doctor --package <target> --json"))
        assertTrue(prompt.contains("obj_* handles and asynchronous tokens"))
        assertFalse(prompt.contains("reverse-engineering"))
        assertFalse(prompt.contains("packages, and runtime state"))
        assertFalse(prompt.contains("Ask the user only when progress genuinely requires"))
    }

    @Test
    fun toolpackCatalogUsesBoundedLazyDiscoveryHints() {
        val commands = (1..19).map { index ->
            ToolpackCommand("frida-cmd-$index", "bin/frida-cmd-$index", "long command description $index")
        }
        val manifest = ToolpackPackageManifest(
            schemaVersion = 1,
            id = "android-frida",
            title = "Android Frida",
            version = "1.0.0",
            architecture = "arm64",
            payloadEntry = ToolpackPackageManifest.PAYLOAD_ENTRY,
            payloadSha256 = "a".repeat(64),
            payloadSizeBytes = 1,
            requiredPaths = listOf("bin/frida-cmd-1", "SKILL.md"),
            commands = commands,
            selfTests = listOf(ToolpackSelfTest("help", "help", "true", setOf(0), emptyList())),
            sources = listOf(
                ToolpackSourceArtifact(
                    "frida",
                    "1.0.0",
                    "https://example.com/frida",
                    "b".repeat(64),
                ),
            ),
        )
        val prompt = MobileAgentPromptBuilder.build(
            MobileAgentRuntimeSession(
                tools = AgentToolSession(emptyList()),
                installedToolpacks = listOf(
                    InstalledToolpack(manifest, "/pkg.zip", "/pack", "rootfs", 1L),
                ),
                workspacePath = "/workspace",
                cancelAllCommands = { 0 },
            ),
        )

        assertTrue(prompt.contains("commands: frida-cmd-1,frida-cmd-2,frida-cmd-3,+16"))
        assertTrue(prompt.contains("skill: /opt/autocrack/toolpacks/active/android-frida/SKILL.md"))
        assertFalse(prompt.contains("frida-cmd-19"))
        assertFalse(prompt.contains("long command description"))
        assertTrue(prompt.length < 6_000)
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
