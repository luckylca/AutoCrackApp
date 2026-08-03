package com.luckylca.autocrack.runtime

import org.junit.Assert.assertThrows
import org.junit.Test

class BuiltInToolpackTrustPolicyTest {
    @Test
    fun acceptsThePinnedApkDexToolpack() {
        BuiltInToolpackTrustPolicy.requireTrusted(trustedApkDexManifest())
    }

    @Test
    fun acceptsThePinnedElfNativeToolpack() {
        BuiltInToolpackTrustPolicy.requireTrusted(trustedElfNativeManifest())
    }

    @Test
    fun acceptsThePinnedRizinToolpack() {
        BuiltInToolpackTrustPolicy.requireTrusted(trustedRizinManifest())
    }

    @Test
    fun rejectsPayloadSubstitution() {
        val manifest = trustedApkDexManifest().copy(
            payloadSha256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        )

        assertThrows(IllegalArgumentException::class.java) {
            BuiltInToolpackTrustPolicy.requireTrusted(manifest)
        }
    }

    @Test
    fun rejectsSelfTestCommandSubstitution() {
        val manifest = trustedApkDexManifest().copy(
            selfTests = trustedApkDexManifest().selfTests.map { test ->
                if (test.id == "java-version") test.copy(command = "rm -rf /workspace") else test
            },
        )

        assertThrows(IllegalArgumentException::class.java) {
            BuiltInToolpackTrustPolicy.requireTrusted(manifest)
        }
    }

    @Test
    fun rejectsUpstreamHashSubstitution() {
        val manifest = trustedApkDexManifest().copy(
            sources = trustedApkDexManifest().sources.map { source ->
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

    @Test
    fun rejectsElfReportCommandSubstitution() {
        val manifest = trustedElfNativeManifest().copy(
            commands = trustedElfNativeManifest().commands.map { command ->
                if (command.name == "elf-report") {
                    command.copy(relativePath = "bin/checksec")
                } else {
                    command
                }
            },
        )

        assertThrows(IllegalArgumentException::class.java) {
            BuiltInToolpackTrustPolicy.requireTrusted(manifest)
        }
    }

    @Test
    fun rejectsChecksecSourceSubstitution() {
        val manifest = trustedElfNativeManifest().copy(
            sources = listOf(
                trustedElfNativeManifest().sources.single().copy(
                    sha256 = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                ),
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            BuiltInToolpackTrustPolicy.requireTrusted(manifest)
        }
    }

    @Test
    fun rejectsRizinPayloadSubstitution() {
        val manifest = trustedRizinManifest().copy(
            payloadSha256 = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
        )

        assertThrows(IllegalArgumentException::class.java) {
            BuiltInToolpackTrustPolicy.requireTrusted(manifest)
        }
    }

    @Test
    fun rejectsRizinCommandSubstitution() {
        val manifest = trustedRizinManifest().copy(
            commands = trustedRizinManifest().commands.map { command ->
                if (command.name == "rz-deep-report") {
                    command.copy(relativePath = "bin/rizin")
                } else {
                    command
                }
            },
        )

        assertThrows(IllegalArgumentException::class.java) {
            BuiltInToolpackTrustPolicy.requireTrusted(manifest)
        }
    }

    @Test
    fun rejectsRizinSourceSubstitution() {
        val manifest = trustedRizinManifest().copy(
            sources = listOf(
                trustedRizinManifest().sources.single().copy(
                    sha256 = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
                ),
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            BuiltInToolpackTrustPolicy.requireTrusted(manifest)
        }
    }

    private fun trustedApkDexManifest(): ToolpackPackageManifest = ToolpackPackageManifest(
        schemaVersion = 1,
        id = "apk-dex-static",
        title = "APK and DEX static analysis",
        version = "jadx-1.5.5_apktool-3.0.2",
        architecture = "all",
        payloadEntry = "payload.zip",
        payloadSha256 = "6c3e1f03f5c653ef63aa137189dcafeb82e4dd40579b3fbb6a5a2a1eb5f2d484",
        payloadSizeBytes = 79_209_842L,
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

    private fun trustedElfNativeManifest(): ToolpackPackageManifest = ToolpackPackageManifest(
        schemaVersion = 1,
        id = "elf-native-static",
        title = "ELF and native static analysis",
        version = "checksec-3.2.0_autocrack-1.0.0",
        architecture = "arm64",
        payloadEntry = "payload.zip",
        payloadSha256 = "4fe3c74c7af905a8586d8ec3cc8157f8e312aec9eef73708818429f5f6910983",
        payloadSizeBytes = 4_459_830L,
        requiredPaths = listOf(
            "bin/checksec",
            "bin/elf-deps",
            "bin/elf-report",
        ),
        commands = listOf(
            ToolpackCommand("checksec", "bin/checksec"),
            ToolpackCommand("elf-deps", "bin/elf-deps"),
            ToolpackCommand("elf-report", "bin/elf-report"),
        ),
        selfTests = listOf(
            ToolpackSelfTest(
                id = "checksec-version",
                title = "Checksec ARM64",
                command = "checksec --version",
                expectedExitCodes = setOf(0),
                outputContains = listOf("3.2.0"),
            ),
            ToolpackSelfTest(
                id = "elf-deps-self-test",
                title = "ELF dependency reporter",
                command = "elf-deps --self-test",
                expectedExitCodes = setOf(0),
                outputContains = listOf("ELF_DEPS_SELF_TEST_OK"),
            ),
            ToolpackSelfTest(
                id = "elf-report-self-test",
                title = "ELF full report generator",
                command = "elf-report --self-test",
                expectedExitCodes = setOf(0),
                outputContains = listOf("ELF_REPORT_SELF_TEST_OK"),
            ),
        ),
        sources = listOf(
            ToolpackSourceArtifact(
                name = "checksec",
                version = "3.2.0",
                url = "https://github.com/slimm609/checksec/releases/download/3.2.0/checksec_3.2.0_arm64.deb",
                sha256 = "4834ac10b87a4faa143fdbf8cc7458be68dbeb9d2e2ec005b669ced7eae9615d",
            ),
        ),
    )

    private fun trustedRizinManifest(): ToolpackPackageManifest = ToolpackPackageManifest(
        schemaVersion = 1,
        id = "rizin-deep-static",
        title = "Rizin deep ELF and native analysis",
        version = "rizin-0.9.1_autocrack-1.0.0",
        architecture = "arm64",
        payloadEntry = "payload.zip",
        payloadSha256 = "3c353c4282ac5266b73390451324f17fb883a5f7563711ccb907ec414c61327d",
        payloadSizeBytes = 60_113_398L,
        requiredPaths = listOf(
            "bin/rizin",
            "bin/rz-functions",
            "bin/rz-disasm",
            "bin/rz-deep-report",
            "lib/rizin/rizin",
        ),
        commands = listOf(
            ToolpackCommand("rizin", "bin/rizin"),
            ToolpackCommand("rz-functions", "bin/rz-functions"),
            ToolpackCommand("rz-disasm", "bin/rz-disasm"),
            ToolpackCommand("rz-deep-report", "bin/rz-deep-report"),
        ),
        selfTests = listOf(
            ToolpackSelfTest(
                id = "rizin-version",
                title = "Rizin ARM64",
                command = "rizin -v",
                expectedExitCodes = setOf(0),
                outputContains = listOf("0.9.1"),
            ),
            ToolpackSelfTest(
                id = "rz-functions-self-test",
                title = "Rizin function inventory",
                command = "rz-functions --self-test",
                expectedExitCodes = setOf(0),
                outputContains = listOf("RZ_FUNCTIONS_SELF_TEST_OK"),
            ),
            ToolpackSelfTest(
                id = "rz-disasm-self-test",
                title = "Rizin bounded disassembly",
                command = "rz-disasm --self-test",
                expectedExitCodes = setOf(0),
                outputContains = listOf("RZ_DISASM_SELF_TEST_OK"),
            ),
            ToolpackSelfTest(
                id = "rz-deep-report-self-test",
                title = "Rizin deep report generator",
                command = "rz-deep-report --self-test",
                expectedExitCodes = setOf(0),
                outputContains = listOf("RZ_DEEP_REPORT_SELF_TEST_OK"),
            ),
        ),
        sources = listOf(
            ToolpackSourceArtifact(
                name = "rizin",
                version = "0.9.1",
                url = "https://github.com/rizinorg/rizin/releases/download/v0.9.1/rizin-v0.9.1-android-aarch64.tar.gz",
                sha256 = "49b96162df17fb0eba443884f8eb0792145646d05c96ac6542e7776a0960fff2",
            ),
        ),
    )
}
