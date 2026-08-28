package com.luckylca.autocrack.runtime

import org.junit.Assert.assertThrows
import org.junit.Test

class BuiltInToolpackTrustPolicyTest {
    @Test
    fun acceptsThePinnedPcapAnalysisToolpack() {
        BuiltInToolpackTrustPolicy.requireTrusted(trustedPcapAnalysisManifest())
    }

    @Test
    fun acceptsThePinnedApkDexToolpack() {
        BuiltInToolpackTrustPolicy.requireTrusted(trustedApkDexManifest())
    }

    @Test
    fun acceptsThePinnedPerfettoToolpack() {
        BuiltInToolpackTrustPolicy.requireTrusted(trustedPerfettoManifest())
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

    private fun trustedPcapAnalysisManifest(): ToolpackPackageManifest = ToolpackPackageManifest(
        schemaVersion = 1,
        id = "rootfs-pcap-analysis",
        title = "Rootfs pcap metadata analysis",
        version = "pcap-summary-1.0.0",
        architecture = "all",
        payloadEntry = "payload.zip",
        payloadSha256 = "cfa34e98e43c6665143acacbedd9b249cdfb0f81c76cccf516b17fd4cffaebe9",
        payloadSizeBytes = 11_703L,
        requiredPaths = listOf("bin/pcap-summary"),
        commands = listOf(ToolpackCommand("pcap-summary", "bin/pcap-summary")),
        selfTests = listOf(
            ToolpackSelfTest(
                id = "pcap-summary-self-test",
                title = "Pure Python pcap summary helper",
                command = "pcap-summary --self-test",
                expectedExitCodes = setOf(0),
                outputContains = listOf("PCAP_SUMMARY_SELF_TEST_OK"),
            ),
        ),
        sources = listOf(
            ToolpackSourceArtifact(
                name = "pcap-summary",
                version = "1.0.0",
                url = "https://github.com/luckylca/AutoCrackApp/tree/main/toolpacks/pcap-analysis",
                sha256 = "5a182f44c7f8f9944e894f2869e5ec3d5312ff4db098a155fdd90f30dec78ad1",
            ),
        ),
    )

    private fun trustedApkDexManifest(): ToolpackPackageManifest = ToolpackPackageManifest(
        schemaVersion = 1,
        id = "apk-dex-static",
        title = "APK and DEX static analysis",
        version = "jadx-1.5.6_apktool-3.0.3",
        architecture = "all",
        payloadEntry = "payload.zip",
        payloadSha256 = "7235f0ab51a59a2232551df2427517a8a6096ba8efb325658ae04a6bf66d0df8",
        payloadSizeBytes = 93_753_109L,
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
                outputContains = listOf("1.5.6"),
            ),
            ToolpackSelfTest(
                id = "apktool-version",
                title = "Apktool",
                command = "apktool --version",
                expectedExitCodes = setOf(0),
                outputContains = listOf("3.0.3"),
            ),
        ),
        sources = listOf(
            ToolpackSourceArtifact(
                name = "jadx",
                version = "1.5.6",
                url = "https://github.com/skylot/jadx/releases/download/v1.5.6/jadx-1.5.6.zip",
                sha256 = "545ea2be9c242511bc145755cf4bda2485ade42966e096f8b4d3da2a230e8974",
            ),
            ToolpackSourceArtifact(
                name = "apktool",
                version = "3.0.3",
                url = "https://github.com/iBotPeaches/Apktool/releases/download/v3.0.3/apktool_3.0.3.jar",
                sha256 = "dbf930b076c6b9be08d57c449cacefc3bdd6b71ebd59b3066fc0e1f5b14f9423",
            ),
        ),
    )

    private fun trustedPerfettoManifest(): ToolpackPackageManifest = ToolpackPackageManifest(
        schemaVersion = 1,
        id = "perfetto-analysis",
        title = "Perfetto trace analysis",
        version = "perfetto-58.2-autocrack-1.0.0",
        architecture = "arm64",
        payloadEntry = "payload.zip",
        payloadSha256 = "087425724070bd58fd41e35aa568ec3874ff8779420196efd2a273c91dfd3ef1",
        payloadSizeBytes = 14_086_296L,
        requiredPaths = listOf("bin/trace_processor"),
        commands = listOf(ToolpackCommand("trace_processor", "bin/trace_processor")),
        selfTests = listOf(
            ToolpackSelfTest(
                id = "trace-processor-help",
                title = "Perfetto trace_processor ARM64 CLI",
                command = "trace_processor --help >/dev/null",
                expectedExitCodes = setOf(0),
                outputContains = emptyList(),
            ),
        ),
        sources = listOf(
            ToolpackSourceArtifact(
                name = "perfetto-linux-arm64",
                version = "58.2",
                url = "https://github.com/google/perfetto/releases/download/v58.2/linux-arm64.zip",
                sha256 = "a82bf4111a340a7ea8577bcfd62e014e8e81b9e6a35a3190f5415fb800051ab0",
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
        version = "rizin-0.9.1_autocrack-1.0.1",
        architecture = "arm64",
        payloadEntry = "payload.zip",
        payloadSha256 = "54d465c8fe84e6f5e5f8be0b56780633f28b2ead84453618fff282ddb50d84b1",
        payloadSizeBytes = 60_113_392L,
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
