package com.luckylca.autocrack.runtime

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SimpleHookToolpackTrustPolicyTest {
    @Test
    fun acceptsPinnedSimpleHookToolpack() {
        BuiltInToolpackTrustPolicy.requireTrusted(trustedManifest())
    }

    @Test
    fun rejectsModifiedSimpleHookPayload() {
        assertThrows(IllegalArgumentException::class.java) {
            BuiltInToolpackTrustPolicy.requireTrusted(
                trustedManifest().copy(payloadSha256 = "a".repeat(64)),
            )
        }
    }

    @Test
    fun rejectsModifiedSimpleHookRequirements() {
        assertThrows(IllegalArgumentException::class.java) {
            BuiltInToolpackTrustPolicy.requireTrusted(
                trustedManifest().copy(
                    requires = trustedManifest().requires.copy(
                        capabilities = listOf("hook.reload"),
                    ),
                ),
            )
        }
    }

    @Test
    fun simpleHookManifestV2RequirementsRoundTrip() {
        val manifest = trustedManifest()
        val roundTrip = ToolpackPackageManifest.parse(manifest.toJson().toString())

        assertTrue(roundTrip.schemaVersion == 2)
        assertTrue(roundTrip.requires.capabilities.contains("hook.inspect"))
        assertTrue(roundTrip.requires.commands.contains("android-shell"))
        BuiltInToolpackTrustPolicy.requireTrusted(roundTrip)
    }

    private fun trustedManifest() = ToolpackPackageManifest(
        schemaVersion = 2,
        id = "simplehook",
        title = "SimpleHook Android Java method debugger",
        version = "simplehook-0.1.1",
        architecture = "all",
        payloadEntry = "payload.zip",
        payloadSha256 = "52de7ef3f08bc698300d7a4abd9163450d0b50356e01ab29210d5f8d6dffaa7b",
        payloadSizeBytes = 43_215L,
        requiredPaths = listOf(
            "bin/simplehook",
            "libexec/simplehook_cli.py",
            "schema/simplehook-rule-v1.schema.json",
            "README.md",
            "VERSION",
        ),
        commands = listOf(
            ToolpackCommand(
                name = "simplehook",
                relativePath = "bin/simplehook",
                description = "Manage Android Java method debug rules, inspect loaded classes, and query JSONL runtime logs.",
            ),
        ),
        selfTests = listOf(
            ToolpackSelfTest(
                id = "simplehook-help",
                title = "SimpleHook CLI command surface",
                command = "/opt/autocrack/toolpacks/active/simplehook/bin/simplehook --help",
                expectedExitCodes = setOf(0),
                outputContains = listOf("rules", "inspect", "environment"),
            ),
            ToolpackSelfTest(
                id = "simplehook-schema-validation",
                title = "SimpleHook v1 example rule validation",
                command = "SIMPLEHOOK_HOME=/tmp/simplehook-self-test /opt/autocrack/toolpacks/active/simplehook/bin/simplehook rules validate /opt/autocrack/toolpacks/active/simplehook/examples/replace-return-int.json --json",
                expectedExitCodes = setOf(0),
                outputContains = listOf("\"valid\":true"),
            ),
        ),
        sources = listOf(
            ToolpackSourceArtifact(
                name = "simplehook-cli",
                version = "0.1.1",
                url = "https://github.com/luckylca/AutoCrackApp/tree/main/toolpacks/simplehook",
                sha256 = "af22a087912fd465b10d5f5bdef651ce38ced92341f40d47b60d2771cb2ed88e",
            ),
        ),
        description = "Manage precise, persistent LSPosed/Xposed Java method debugging rules and structured logs for authorized Android test applications.",
        requires = ToolpackRequirements(
            runtime = ">=1.0.0",
            capabilities = listOf("hook.reload", "hook.inspect"),
            commands = listOf("android-shell"),
            optionalCapabilities = listOf(
                "runtime.process",
                "runtime.class.search",
                "runtime.class.describe",
            ),
        ),
    )
}
