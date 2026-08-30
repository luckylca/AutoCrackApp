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
        title = "Android Frida dynamic instrumentation",
        version = "frida-17.17.0-autocrack-1.1.0",
        architecture = "arm64",
        payloadEntry = "payload.zip",
        payloadSha256 = "c4da46d6d03a1b88d9d6031631ada4cf4ec52405e79aaf487f8ac2f5369055c9",
        payloadSizeBytes = 147_144_577L,
        requiredPaths = listOf(
            "bin/frida-server-android",
            "bin/android-frida-server",
            "bin/frida-autocrack-client",
            "libexec/autocrack-frida-agent.js",
            "libexec/frida_autocrack_client.py",
            "libexec/frida_tools_launcher.py",
            "bin/frida",
            "bin/frida-ps",
            "bin/frida-trace",
        ),
        commands = listOf(
            ToolpackCommand("android-frida-server", "bin/android-frida-server"),
            ToolpackCommand("frida-autocrack-client", "bin/frida-autocrack-client"),
            ToolpackCommand("frida", "bin/frida"),
            ToolpackCommand("frida-ls-devices", "bin/frida-ls-devices"),
            ToolpackCommand("frida-ps", "bin/frida-ps"),
            ToolpackCommand("frida-kill", "bin/frida-kill"),
            ToolpackCommand("frida-ls", "bin/frida-ls"),
            ToolpackCommand("frida-rm", "bin/frida-rm"),
            ToolpackCommand("frida-pull", "bin/frida-pull"),
            ToolpackCommand("frida-push", "bin/frida-push"),
            ToolpackCommand("frida-discover", "bin/frida-discover"),
            ToolpackCommand("frida-trace", "bin/frida-trace"),
            ToolpackCommand("frida-strace", "bin/frida-strace"),
            ToolpackCommand("frida-itrace", "bin/frida-itrace"),
            ToolpackCommand("frida-join", "bin/frida-join"),
            ToolpackCommand("frida-create", "bin/frida-create"),
            ToolpackCommand("frida-compile", "bin/frida-compile"),
            ToolpackCommand("frida-pm", "bin/frida-pm"),
            ToolpackCommand("frida-apk", "bin/frida-apk"),
        ),
        selfTests = listOf(
            ToolpackSelfTest(
                id = "frida-server-android-binary",
                title = "Official Android ARM64 Frida server payload",
                command = "test -x /opt/autocrack/toolpacks/active/android-frida/bin/frida-server-android && printf \"AUTOCRACK_FRIDA_SERVER_BINARY_OK\\n\"",
                expectedExitCodes = setOf(0),
                outputContains = listOf("AUTOCRACK_FRIDA_SERVER_BINARY_OK"),
            ),
            ToolpackSelfTest(
                id = "android-frida-server-help",
                title = "Android Frida server lifecycle helper",
                command = "android-frida-server 2>&1 || test $? -eq 2",
                expectedExitCodes = setOf(0),
                outputContains = listOf("start|status|stop"),
            ),
            ToolpackSelfTest(
                id = "frida-python-import",
                title = "ARM64 Frida Python binding import",
                command = "PYTHONDONTWRITEBYTECODE=1 python3 -B -c \"import frida; print(frida.__version__)\"",
                expectedExitCodes = setOf(0),
                outputContains = listOf("17.17.0"),
            ),
            ToolpackSelfTest(
                id = "frida-upstream-cli-version",
                title = "Upstream Frida CLI",
                command = "frida --version",
                expectedExitCodes = setOf(0),
                outputContains = listOf("17.17.0"),
            ),
            ToolpackSelfTest(
                id = "frida-autocrack-client-help",
                title = "Optional AutoCrack Frida helper",
                command = "/opt/autocrack/toolpacks/active/android-frida/bin/frida-autocrack-client --help",
                expectedExitCodes = setOf(0),
                outputContains = listOf("native-trace", "tls-trace", "java-field-write"),
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
                name = "frida-tools",
                version = "14.10.4",
                url = "https://pypi.org/project/frida-tools/14.10.4/",
                sha256 = "7a2c544b545d095040fffbd3768a287a426343dad89095b4a24f4b20382d926a",
            ),
            ToolpackSourceArtifact(
                name = "colorama",
                version = "0.4.6",
                url = "https://pypi.org/project/colorama/0.4.6/",
                sha256 = "4f1d9991f5acc0ca119f9d443620b77f9d6b33703e51011c16baf57afb285fc6",
            ),
            ToolpackSourceArtifact(
                name = "prompt-toolkit",
                version = "3.0.53",
                url = "https://pypi.org/project/prompt-toolkit/3.0.53/",
                sha256 = "01c0891d7f9237d5e339f7d3e42cdae80b7534abb1c7c0e3352efba6231492f2",
            ),
            ToolpackSourceArtifact(
                name = "pygments",
                version = "2.21.0",
                url = "https://pypi.org/project/Pygments/2.21.0/",
                sha256 = "2363c69b61c4a97c838da3b130dcd6468f4848992b21a82f2a63ec34377137d9",
            ),
            ToolpackSourceArtifact(
                name = "wcwidth",
                version = "0.8.3",
                url = "https://pypi.org/project/wcwidth/0.8.3/",
                sha256 = "d5b73dba6158a595ec9370350e7f2637bcac8d6c5e4fde34f30fcffb6103a5e4",
            ),
            ToolpackSourceArtifact(
                name = "websockets",
                version = "13.1",
                url = "https://pypi.org/project/websockets/13.1/",
                sha256 = "308e20f22c2c77f3f39caca508e765f8725020b84aa963474e18c59accbf4c02",
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
