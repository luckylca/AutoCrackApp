package com.luckylca.autocrack.runtime

import org.junit.Assert.assertThrows
import org.junit.Test

class BuiltInToolpackTrustPolicyTest {
    @Test
    fun acceptsThePinnedApkDexToolpack() {
        BuiltInToolpackTrustPolicy.requireTrusted(trustedManifest())
    }

    @Test
    fun rejectsPayloadSubstitution() {
        val manifest = trustedManifest().copy(
            payloadSha256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        )

        assertThrows(IllegalArgumentException::class.java) {
            BuiltInToolpackTrustPolicy.requireTrusted(manifest)
        }
    }

    @Test
    fun rejectsSelfTestCommandSubstitution() {
        val manifest = trustedManifest().copy(
            selfTests = trustedManifest().selfTests.map { test ->
                if (test.id == "java-version") test.copy(command = "rm -rf /workspace") else test
            },
        )

        assertThrows(IllegalArgumentException::class.java) {
            BuiltInToolpackTrustPolicy.requireTrusted(manifest)
        }
    }

    @Test
    fun rejectsUpstreamHashSubstitution() {
        val manifest = trustedManifest().copy(
            sources = trustedManifest().sources.map { source ->
                if (source.name == "jadx") {
                    source.copy(
                        sha256 = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                    )
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
        id = "apk-dex-static",
        title = "APK and DEX static analysis",
        version = "jadx-1.5.5_apktool-3.0.2",
        architecture = "all",
        payloadEntry = "payload.zip",
        payloadSha256 = "33bcc0d69da0a72ca232e345fe78b5e2da5b96925fd9ea499f47dd5020e03734",
        payloadSizeBytes = 73_549_035L,
        requiredPaths = listOf(
            "bin/jadx",
            "bin/apktool",
            "lib/jadx/bin/jadx",
            "lib/apktool/apktool.jar",
        ),
        commands = listOf(
            ToolpackCommand("jadx", "bin/jadx"),
            ToolpackCommand("apktool", "bin/apktool"),
        ),
        selfTests = listOf(
            ToolpackSelfTest(
                id = "java-version",
                title = "Java runtime",
                command = "java -version",
                expectedExitCodes = setOf(0),
                outputContains = listOf("version"),
            ),
            ToolpackSelfTest(
                id = "jadx-version",
                title = "JADX CLI",
                command = "jadx --version",
                expectedExitCodes = setOf(0),
                outputContains = listOf("1.5.5"),
            ),
            ToolpackSelfTest(
                id = "apktool-version",
                title = "Apktool",
                command = "apktool --version",
                expectedExitCodes = setOf(0),
                outputContains = listOf("3.0.2"),
            ),
        ),
        sources = listOf(
            ToolpackSourceArtifact(
                name = "jadx",
                version = "1.5.5",
                url = "https://github.com/skylot/jadx/releases/download/v1.5.5/jadx-1.5.5.zip",
                sha256 = "38a5766d3c8170c41566b4b13ea0ede2430e3008421af4927235c2880234d51a",
            ),
            ToolpackSourceArtifact(
                name = "apktool",
                version = "3.0.2",
                url = "https://github.com/iBotPeaches/Apktool/releases/download/v3.0.2/apktool_3.0.2.jar",
                sha256 = "eee4669a704a14e0623407e6701b0b91887e61e1e4049cb7a82833e14ae8b5fd",
            ),
        ),
    )
}
