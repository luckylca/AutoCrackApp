package com.luckylca.autocrack.runtime

import org.junit.Assert.assertThrows
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

    private fun trustedManifest() = ToolpackPackageManifest(
        schemaVersion = 1,
        id = "simplehook",
        title = "SimpleHook Android Java method debugger",
        version = "simplehook-0.1.0",
        architecture = "all",
        payloadEntry = "payload.zip",
        payloadSha256 = "8a9539f7cc496843df92e23304b636bbe83e65eee1012447517d055d5fbb7ecd",
        payloadSizeBytes = 35_962L,
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
                version = "0.1.0",
                url = "https://github.com/luckylca/AutoCrackApp/tree/main/toolpacks/simplehook",
                sha256 = "c051b9b084b65374859e8caf7fc3d1f31476320646ed5383c7fd0023409ba213",
            ),
        ),
        description = "Manage precise, persistent LSPosed/Xposed Java method debugging rules and structured logs for authorized Android test applications.",
    )
}
