package com.luckylca.autocrack.runtime

import org.junit.Assert.assertThrows
import org.junit.Test

class AndroidLldbToolpackTrustPolicyTest {
    @Test
    fun acceptsPinnedAndroidLldbServerToolpack() {
        BuiltInToolpackTrustPolicy.requireTrusted(trustedManifest())
    }

    @Test
    fun rejectsLldbServerPayloadSubstitution() {
        val manifest = trustedManifest().copy(
            payloadSha256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        )

        assertThrows(IllegalArgumentException::class.java) {
            BuiltInToolpackTrustPolicy.requireTrusted(manifest)
        }
    }

    @Test
    fun rejectsLldbServerSourceSubstitution() {
        val manifest = trustedManifest().copy(
            sources = listOf(
                trustedManifest().sources.single().copy(
                    sha256 = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                ),
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            BuiltInToolpackTrustPolicy.requireTrusted(manifest)
        }
    }

    private fun trustedManifest(): ToolpackPackageManifest = ToolpackPackageManifest(
        schemaVersion = 1,
        id = "android-lldb-server",
        title = "Android LLDB server",
        version = "android-llvm-r522817_autocrack-1.3.0-seize-runtime-stop",
        architecture = "arm64",
        payloadEntry = "payload.zip",
        payloadSha256 = "f2d3b3925ffc49419508dd97cd657d4a8a2e0b0b313f473105173b96ce31b899",
        payloadSizeBytes = 28_396_656L,
        requiredPaths = listOf("bin/lldb-server-android"),
        commands = listOf(
            ToolpackCommand("lldb-server-android", "bin/lldb-server-android"),
        ),
        selfTests = listOf(
            ToolpackSelfTest(
                id = "lldb-server-android-binary",
                title = "Android LLDB server payload",
                command = "test -x /opt/autocrack/toolpacks/packs/android-lldb-server/android-llvm-r522817_autocrack-1.3.0-seize-runtime-stop/bin/lldb-server-android && printf \"AUTOCRACK_LLDB_ANDROID_BINARY_OK\\n\"",
                expectedExitCodes = setOf(0),
                outputContains = listOf("AUTOCRACK_LLDB_ANDROID_BINARY_OK"),
            ),
        ),
        sources = listOf(
            ToolpackSourceArtifact(
                name = "lldb-server",
                version = "android-llvm-r522817-autocrack-seize-runtime-stop",
                url = "https://android.googlesource.com/toolchain/llvm-project/+/d8003a456d14a3deb8054cdaa529ffbf02d9b262",
                sha256 = "71d9ed6a90776d7dbdbcb315ea2171a763c071e5a370ec1b8b0c28157af41b20",
            ),
        ),
    )
}
