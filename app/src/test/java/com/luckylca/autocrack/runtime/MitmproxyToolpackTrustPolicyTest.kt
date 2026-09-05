package com.luckylca.autocrack.runtime

import org.junit.Assert.assertThrows
import org.junit.Test

class MitmproxyToolpackTrustPolicyTest {
    @Test
    fun acceptsPinnedOfficialMitmproxyToolpack() {
        BuiltInToolpackTrustPolicy.requireTrusted(manifest())
    }

    @Test
    fun rejectsMitmproxyPayloadMutation() {
        assertThrows(IllegalArgumentException::class.java) {
            BuiltInToolpackTrustPolicy.requireTrusted(
                manifest().copy(
                    payloadSha256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                ),
            )
        }
    }

    @Test
    fun rejectsMitmproxySourceMutation() {
        val original = manifest()
        assertThrows(IllegalArgumentException::class.java) {
            BuiltInToolpackTrustPolicy.requireTrusted(
                original.copy(
                    sources = original.sources.map { source ->
                        source.copy(
                            sha256 = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                        )
                    },
                ),
            )
        }
    }

    private fun manifest(): ToolpackPackageManifest = ToolpackPackageManifest(
        schemaVersion = 2,
        id = "mitmproxy",
        title = "mitmproxy complete Linux ARM64 interception suite",
        version = "mitmproxy-12.2.3-linux-aarch64_autocrack-1.0.0",
        architecture = "arm64",
        payloadEntry = "payload.zip",
        payloadSha256 = "79dc61fa447ac820c0026e36268559955e4a0a1ed37da2c9726e64a353a27cfb",
        payloadSizeBytes = 113_846_151L,
        requiredPaths = listOf(
            "bin/mitmproxy",
            "bin/mitmdump",
            "bin/mitmweb",
            "examples/autocrack_addon_smoke.py",
            "SKILL.md",
            "VERSION",
        ),
        commands = listOf(
            ToolpackCommand("mitmproxy", "bin/mitmproxy"),
            ToolpackCommand("mitmdump", "bin/mitmdump"),
            ToolpackCommand("mitmweb", "bin/mitmweb"),
        ),
        selfTests = listOf(
            ToolpackSelfTest(
                id = "mitmproxy-version",
                title = "Official mitmproxy standalone version",
                command = "mitmproxy --version",
                expectedExitCodes = setOf(0),
                outputContains = listOf("Mitmproxy: 12.2.3"),
            ),
            ToolpackSelfTest(
                id = "mitmdump-addon-api",
                title = "Embedded upstream Python addon API",
                command = "mitmdump -q -s /opt/autocrack/toolpacks/active/mitmproxy/examples/autocrack_addon_smoke.py",
                expectedExitCodes = setOf(0),
                outputContains = listOf("AUTOCRACK_MITMPROXY_ADDON_API_OK"),
            ),
            ToolpackSelfTest(
                id = "mitmweb-help",
                title = "Official mitmweb command",
                command = "mitmweb --help",
                expectedExitCodes = setOf(0, 1),
                outputContains = listOf("usage:", "mitmweb"),
            ),
        ),
        sources = listOf(
            ToolpackSourceArtifact(
                name = "mitmproxy-linux-aarch64",
                version = "12.2.3",
                url = "https://downloads.mitmproxy.org/12.2.3/mitmproxy-12.2.3-linux-aarch64.tar.gz",
                sha256 = "b358643a6c4f4b39e33d985350f660b724fece95687d7daa899ef0c4e211f681",
            ),
        ),
        requires = ToolpackRequirements(),
    )
}
