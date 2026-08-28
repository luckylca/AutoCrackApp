package com.luckylca.autocrack.runtime

import org.junit.Assert.assertThrows
import org.junit.Test

class AndroidFridaToolpackTrustPolicyTest {
    @Test
    fun acceptsPinnedAndroidFridaToolpack() {
        BuiltInToolpackTrustPolicy.requireTrusted(trustedManifest())
    }

    @Test
    fun rejectsFridaPayloadSubstitution() {
        val manifest = trustedManifest().copy(
            payloadSha256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        )

        assertThrows(IllegalArgumentException::class.java) {
            BuiltInToolpackTrustPolicy.requireTrusted(manifest)
        }
    }

    @Test
    fun rejectsFridaJavaBridgeSubstitution() {
        val original = trustedManifest()
        val manifest = original.copy(
            sources = original.sources.map { source ->
                if (source.name == "frida-java-bridge") {
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
        id = "android-frida",
        title = "Android Frida bounded dynamic instrumentation",
        version = "frida-17.17.0-autocrack-1.0.3",
        architecture = "arm64",
        payloadEntry = "payload.zip",
        payloadSha256 = "67664cbef3a5b4b77f75b2e85102c53589ea9c475b520bda4c64c08949a0468d",
        payloadSizeBytes = 124_957_234L,
        requiredPaths = listOf(
            "bin/frida-server-android",
            "bin/frida-autocrack-client",
            "libexec/autocrack-frida-agent.js",
            "libexec/frida_autocrack_client.py",
        ),
        commands = listOf(
            ToolpackCommand("frida-server-android", "bin/frida-server-android"),
            ToolpackCommand("frida-autocrack-client", "bin/frida-autocrack-client"),
        ),
        selfTests = listOf(
            ToolpackSelfTest(
                id = "frida-server-android-binary",
                title = "Official Android ARM64 Frida server payload",
                command = "test -x /opt/autocrack/toolpacks/packs/android-frida/frida-17.17.0-autocrack-1.0.3/bin/frida-server-android && printf \"AUTOCRACK_FRIDA_SERVER_BINARY_OK\\n\"",
                expectedExitCodes = setOf(0),
                outputContains = listOf("AUTOCRACK_FRIDA_SERVER_BINARY_OK"),
            ),
            ToolpackSelfTest(
                id = "frida-python-import",
                title = "ARM64 Frida Python binding import",
                command = "PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=/opt/autocrack/toolpacks/packs/android-frida/frida-17.17.0-autocrack-1.0.3/python python3 -B -c \"import frida; print(frida.__version__)\"",
                expectedExitCodes = setOf(0),
                outputContains = listOf("17.17.0"),
            ),
            ToolpackSelfTest(
                id = "frida-bounded-client-help",
                title = "Bounded AutoCrack Frida client",
                command = "/opt/autocrack/toolpacks/packs/android-frida/frida-17.17.0-autocrack-1.0.3/bin/frida-autocrack-client --help",
                expectedExitCodes = setOf(0),
                outputContains = listOf("native-trace", "tls-trace"),
            ),
        ),
        sources = listOf(
            ToolpackSourceArtifact(
                name = "frida-server-android-arm64",
                version = "17.17.0",
                url = "https://github.com/frida/frida/releases/download/17.17.0/frida-server-17.17.0-android-arm64.xz",
                sha256 = "09d1fad867b27d69562a79289f4c412e85867f5d38ab72877036ed35e4223021",
            ),
            ToolpackSourceArtifact(
                name = "frida-python-aarch64",
                version = "17.17.0",
                url = "https://pypi.org/project/frida/17.17.0/",
                sha256 = "82ddfa720588a0429fd3dd8e75ccf5c722d57da3d5544d1ba420741c032ba7a8",
            ),
            ToolpackSourceArtifact(
                name = "frida-java-bridge",
                version = "7.0.13",
                url = "https://registry.npmjs.org/frida-java-bridge/-/frida-java-bridge-7.0.13.tgz",
                sha256 = "0ae4e5393b5bf237ba7cfe23666248a7e6884cc9321ac0a77f073bcb30d951c0",
            ),
        ),
    )
}
