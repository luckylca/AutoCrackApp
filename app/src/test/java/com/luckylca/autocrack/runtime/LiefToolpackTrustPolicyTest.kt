package com.luckylca.autocrack.runtime

import org.junit.Assert.assertThrows
import org.junit.Test

class LiefToolpackTrustPolicyTest {
    @Test
    fun acceptsPinnedLiefToolpack() {
        BuiltInToolpackTrustPolicy.requireTrusted(trustedManifest())
    }

    @Test
    fun rejectsLiefPayloadSubstitution() {
        val manifest = trustedManifest().copy(
            payloadSha256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        )
        assertThrows(IllegalArgumentException::class.java) {
            BuiltInToolpackTrustPolicy.requireTrusted(manifest)
        }
    }

    @Test
    fun rejectsLiefWheelSubstitution() {
        val original = trustedManifest()
        val manifest = original.copy(
            sources = original.sources.map { source ->
                source.copy(sha256 = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
            },
        )
        assertThrows(IllegalArgumentException::class.java) {
            BuiltInToolpackTrustPolicy.requireTrusted(manifest)
        }
    }

    private fun trustedManifest(): ToolpackPackageManifest = ToolpackPackageManifest(
        schemaVersion = 1,
        id = "lief-static",
        title = "LIEF full Python API and ELF report helper",
        version = "lief-1.0.0-autocrack-1.0.0",
        architecture = "arm64",
        payloadEntry = "payload.zip",
        payloadSha256 = "ca01f7b80573b459f9a55658d4a493475b401e154b5aa9e80d1a7696ad5d6f15",
        payloadSizeBytes = 12_245_708L,
        requiredPaths = listOf(
            "bin/lief-elf-report",
            "libexec/lief_elf_report.py",
            "python/lief/__init__.py",
        ),
        commands = listOf(ToolpackCommand("lief-elf-report", "bin/lief-elf-report")),
        selfTests = listOf(
            ToolpackSelfTest(
                id = "lief-python-import",
                title = "Complete LIEF Python API import",
                command = "PYTHONDONTWRITEBYTECODE=1 python3 -B -c \"import lief; print(lief.__version__)\"",
                expectedExitCodes = setOf(0),
                outputContains = listOf("1.0.0"),
            ),
            ToolpackSelfTest(
                id = "lief-static-self-test",
                title = "Optional bounded ELF report helper",
                command = "lief-elf-report --self-test",
                expectedExitCodes = setOf(0),
                outputContains = listOf("LIEF_STATIC_SELF_TEST_OK version=1.0.0"),
            ),
        ),
        sources = listOf(
            ToolpackSourceArtifact(
                name = "lief-python-aarch64",
                version = "1.0.0",
                url = "https://files.pythonhosted.org/packages/6f/21/097f7f28157870491d648c65befd3a66163eb42f23bd12d1bbda59e94c5e/lief-1.0.0-cp311-cp311-manylinux_2_28_aarch64.whl",
                sha256 = "95fb7dc84960068ab881bc681e646cab55f3d736c07bb07e91d5cbc8738885a7",
            ),
        ),
    )
}
