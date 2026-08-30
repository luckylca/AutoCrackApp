package com.luckylca.autocrack.runtime

import org.junit.Assert.assertThrows
import org.junit.Test

class AndroidPcapToolpackTrustPolicyTest {
    @Test
    fun acceptsPinnedAndroidPcapToolpack() {
        BuiltInToolpackTrustPolicy.requireTrusted(trustedManifest())
    }

    @Test
    fun rejectsTcpdumpPayloadSubstitution() {
        val manifest = trustedManifest().copy(
            payloadSha256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        )

        assertThrows(IllegalArgumentException::class.java) {
            BuiltInToolpackTrustPolicy.requireTrusted(manifest)
        }
    }

    @Test
    fun rejectsLibpcapSourceSubstitution() {
        val original = trustedManifest()
        val manifest = original.copy(
            sources = original.sources.map { source ->
                if (source.name == "libpcap") {
                    source.copy(sha256 = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
                } else {
                    source
                }
            },
        )

        assertThrows(IllegalArgumentException::class.java) {
            BuiltInToolpackTrustPolicy.requireTrusted(manifest)
        }
    }

    private fun trustedManifest(): ToolpackPackageManifest = ToolpackPackageManifest(
        schemaVersion = 1,
        id = "android-pcap-helper",
        title = "Android host tcpdump",
        version = "tcpdump-4.99.5_libpcap-1.10.5_autocrack-1.1.0",
        architecture = "arm64",
        payloadEntry = "payload.zip",
        payloadSha256 = "0090c82f039f2a61b69533eaaa9063611f6bd5bd1a71992b4794aa94bef7db9e",
        payloadSizeBytes = 3_945_278L,
        requiredPaths = listOf("bin/tcpdump", "host-bin/tcpdump"),
        commands = listOf(ToolpackCommand("tcpdump", "bin/tcpdump")),
        selfTests = listOf(
            ToolpackSelfTest(
                id = "tcpdump-binary",
                title = "Android tcpdump binary",
                command = "test -x /opt/autocrack/toolpacks/active/android-pcap-helper/host-bin/tcpdump && printf 'AUTOCRACK_TCPDUMP_BINARY_OK\\n'",
                expectedExitCodes = setOf(0),
                outputContains = listOf("AUTOCRACK_TCPDUMP_BINARY_OK"),
            ),
            ToolpackSelfTest(
                id = "tcpdump-launcher",
                title = "Standard tcpdump Android-host launcher",
                command = "test -x /opt/autocrack/toolpacks/active/android-pcap-helper/bin/tcpdump && grep -F '\"$@\"' /opt/autocrack/toolpacks/active/android-pcap-helper/bin/tcpdump",
                expectedExitCodes = setOf(0),
                outputContains = listOf("$@"),
            ),
        ),
        sources = listOf(
            ToolpackSourceArtifact(
                name = "tcpdump",
                version = "4.99.5",
                url = "https://www.tcpdump.org/release/tcpdump-4.99.5.tar.xz",
                sha256 = "d76395ab82d659d526291b013eee200201380930793531515abfc6e77b4f2ee5",
            ),
            ToolpackSourceArtifact(
                name = "libpcap",
                version = "1.10.5",
                url = "https://www.tcpdump.org/release/libpcap-1.10.5.tar.xz",
                sha256 = "84fa89ac6d303028c1c5b754abff77224f45eca0a94eb1a34ff0aa9ceece3925",
            ),
        ),
    )
}
