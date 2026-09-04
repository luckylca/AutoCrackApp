package com.luckylca.autocrack.runtime

import org.junit.Assert.assertThrows
import org.junit.Test

class ApkidToolpackTrustPolicyTest {
    @Test
    fun acceptsPinnedCompleteApkidToolpack() {
        BuiltInToolpackTrustPolicy.requireTrusted(manifest())
    }

    @Test
    fun rejectsApkidRulePayloadMutation() {
        assertThrows(IllegalArgumentException::class.java) {
            BuiltInToolpackTrustPolicy.requireTrusted(
                manifest().copy(
                    payloadSha256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                ),
            )
        }
    }

    @Test
    fun rejectsApkidSourceMutation() {
        assertThrows(IllegalArgumentException::class.java) {
            BuiltInToolpackTrustPolicy.requireTrusted(
                manifest().copy(
                    sources = manifest().sources.map { source ->
                        if (source.name == "apkid-wheel") {
                            source.copy(
                                sha256 = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                            )
                        } else {
                            source
                        }
                    },
                ),
            )
        }
    }

    private fun manifest(): ToolpackPackageManifest = ToolpackPackageManifest(
        schemaVersion = 2,
        id = "apkid",
        title = "APKiD full Android packer and protection identification",
        version = "apkid-3.1.0_yara-python-dex-1.0.7_autocrack-1.0.0",
        architecture = "arm64",
        payloadEntry = "payload.zip",
        payloadSha256 = "dd28ced9b9a616a43ef8bdd1cbe37a09c5eb03e1e1e405a0449c137ece8f5518",
        payloadSizeBytes = 6_384_645L,
        requiredPaths = listOf(
            "bin/apkid",
            "python/apkid/main.py",
            "python/apkid/rules/rules.yarc",
            "SKILL.md",
        ),
        commands = listOf(
            ToolpackCommand("apkid", "bin/apkid"),
        ),
        selfTests = listOf(
            ToolpackSelfTest(
                id = "apkid-version",
                title = "Upstream APKiD CLI",
                command = "apkid --help",
                expectedExitCodes = setOf(0),
                outputContains = listOf("APKiD - Android Application Identifier v3.1.0"),
            ),
            ToolpackSelfTest(
                id = "apkid-python-api",
                title = "Complete APKiD Python API and rules",
                command = "PYTHONDONTWRITEBYTECODE=1 python3 -B -c \"import apkid,apkid.apkid,apkid.rules,yara;print('AUTOCRACK_APKID_API_OK')\"",
                expectedExitCodes = setOf(0),
                outputContains = listOf("AUTOCRACK_APKID_API_OK"),
            ),
        ),
        sources = listOf(
            ToolpackSourceArtifact(
                name = "apkid-wheel",
                version = "3.1.0",
                url = "https://pypi.org/project/apkid/3.1.0/",
                sha256 = "02e349865bc1005ae2beb27fbb58acdeabb56d1a60ce723c344cde1bb32896f8",
            ),
            ToolpackSourceArtifact(
                name = "yara-python-dex-linux-aarch64",
                version = "1.0.7",
                url = "https://pypi.org/project/yara-python-dex/1.0.7/",
                sha256 = "a0176641510cff158ab56fd60f8d3b67ffd804441def44df53a87a0632090225",
            ),
        ),
        requires = ToolpackRequirements(),
    )
}
