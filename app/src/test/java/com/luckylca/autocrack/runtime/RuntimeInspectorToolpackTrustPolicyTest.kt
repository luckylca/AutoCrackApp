package com.luckylca.autocrack.runtime

import org.junit.Assert.assertThrows
import org.junit.Test

class RuntimeInspectorToolpackTrustPolicyTest {
    @Test
    fun acceptsPinnedRuntimeInspectorToolpack() {
        BuiltInToolpackTrustPolicy.requireTrusted(trustedManifest())
    }

    @Test
    fun rejectsModifiedRuntimeInspectorPayload() {
        assertThrows(IllegalArgumentException::class.java) {
            BuiltInToolpackTrustPolicy.requireTrusted(
                trustedManifest().copy(payloadSha256 = "a".repeat(64)),
            )
        }
    }

    private fun trustedManifest() = ToolpackPackageManifest(
        schemaVersion = 1,
        id = "runtime-inspector",
        title = "Android Runtime View Inspector",
        version = "runtime-inspector-0.1.0",
        architecture = "all",
        payloadEntry = "payload.zip",
        payloadSha256 = "e1e6cc14ebb0f0c7e75347d903c3e96c28578894c41d54bc3ab583839b6f683f",
        payloadSizeBytes = 8_751L,
        requiredPaths = listOf(
            "bin/runtime-inspector",
            "libexec/runtime_inspector_cli.py",
            "README.md",
            "VERSION",
        ),
        commands = listOf(
            ToolpackCommand(
                name = "runtime-inspector",
                relativePath = "bin/runtime-inspector",
                description = "Inspect live windows, Views, listeners, coordinates, and bounded UI actions.",
            ),
        ),
        selfTests = listOf(
            ToolpackSelfTest(
                id = "runtime-inspector-help",
                title = "Runtime Inspector CLI surface",
                command = "/opt/autocrack/toolpacks/active/runtime-inspector/bin/runtime-inspector --help",
                expectedExitCodes = setOf(0),
                outputContains = listOf("windows", "tree", "action"),
            ),
        ),
        sources = listOf(
            ToolpackSourceArtifact(
                name = "runtime-inspector-cli",
                version = "0.1.0",
                url = "https://github.com/luckylca/AutoCrackApp/tree/main/toolpacks/runtime-inspector",
                sha256 = "bc1c2c7f9c7ded939f7a515dcb6022c6711b4c3ec74fb1c83c1b4cd6625e0e44",
            ),
        ),
        description = "Inspect live Android windows and View hierarchies in authorized LSPosed-scoped apps.",
    )
}
