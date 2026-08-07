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
        version = "ndk-r27d-clang-r522817d_autocrack-1.0.0",
        architecture = "arm64",
        payloadEntry = "payload.zip",
        payloadSha256 = "2cc969ff785e8c0c3d4473649edaaf18f64faae4c7e016941dd0c0944944a14a",
        payloadSizeBytes = 27_698_528L,
        requiredPaths = listOf("bin/lldb-server-android"),
        commands = listOf(
            ToolpackCommand("lldb-server-android", "bin/lldb-server-android"),
        ),
        selfTests = listOf(
            ToolpackSelfTest(
                id = "lldb-server-version",
                title = "Android LLDB server",
                command = "lldb-server-android v",
                expectedExitCodes = setOf(0),
                outputContains = listOf("lldb"),
            ),
        ),
        sources = listOf(
            ToolpackSourceArtifact(
                name = "lldb-server",
                version = "ndk-r27d-clang-r522817d",
                url = "https://github.com/android/ndk/releases/tag/r27d",
                sha256 = "ff96d83baa872b2226bb1f4f38cd38aa2622416722fb76543cc536edfeea3018",
            ),
        ),
    )
}
