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
        version = "simplehook-0.1.1",
        architecture = "all",
        payloadEntry = "payload.zip",
        payloadSha256 = "f4aaf2f32899e1ba2023dd21e7342cd0c3c5ac5550d80a63d64785f27a348d7d",
        payloadSizeBytes = 42_763L,
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
                sha256 = "1c7fcc4e36f4500af7a7d7c0ff4bb0add2481433847204b8c7ab47512e8285ae",
            ),
        ),
        description = "Manage precise, persistent LSPosed/Xposed Java method debugging rules and structured logs for authorized Android test applications.",
    )
}
