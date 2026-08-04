package com.luckylca.autocrack.runtime

internal object BuiltInToolpackTrustPolicy {
    private data class TrustedToolpack(
        val title: String,
        val version: String,
        val architecture: String,
        val payloadSha256: String,
        val payloadSizeBytes: Long,
        val requiredPaths: Set<String>,
        val commands: Map<String, String>,
        val selfTests: Map<String, TrustedSelfTest>,
        val sources: Map<String, TrustedSource>,
    )

    private data class TrustedSelfTest(
        val title: String,
        val command: String,
        val expectedExitCodes: Set<Int>,
        val outputContains: List<String>,
    )

    private data class TrustedSource(
        val version: String,
        val url: String,
        val sha256: String,
    )

    fun requireTrusted(manifest: ToolpackPackageManifest) {
        val trusted = TRUSTED_TOOLPACKS[manifest.id]
            ?: error("工具包不在 AutoCrackApp 内置信任目录中：${manifest.id}")

        require(manifest.title == trusted.title) { "工具包标题与内置信任目录不一致" }
        require(manifest.version == trusted.version) { "工具包版本未被信任：${manifest.version}" }
        require(manifest.architecture == trusted.architecture) { "工具包架构与内置信任目录不一致" }
        require(manifest.payloadSha256 == trusted.payloadSha256) {
            "工具包 payload SHA-256 未被信任"
        }
        require(manifest.payloadSizeBytes == trusted.payloadSizeBytes) {
            "工具包 payload 大小与内置信任目录不一致"
        }
        require(manifest.requiredPaths.toSet() == trusted.requiredPaths) {
            "工具包必需路径与内置信任目录不一致"
        }
        require(
            manifest.commands.associate { command -> command.name to command.relativePath } ==
                trusted.commands,
        ) { "工具包命令目录与内置信任目录不一致" }

        val actualSelfTests = manifest.selfTests.associate { test ->
            test.id to TrustedSelfTest(
                title = test.title,
                command = test.command,
                expectedExitCodes = test.expectedExitCodes,
                outputContains = test.outputContains,
            )
        }
        require(actualSelfTests == trusted.selfTests) {
            "工具包自检定义与内置信任目录不一致"
        }

        val actualSources = manifest.sources.associate { source ->
            source.name to TrustedSource(
                version = source.version,
                url = source.url,
                sha256 = source.sha256,
            )
        }
        require(actualSources == trusted.sources) {
            "工具包来源或上游 SHA-256 与内置信任目录不一致"
        }
    }

    private val TRUSTED_TOOLPACKS = mapOf(
        "apk-dex-static" to TrustedToolpack(
            title = "APK and DEX static analysis",
            version = "jadx-1.5.5_apktool-3.0.2",
            architecture = "all",
            payloadSha256 = "6c3e1f03f5c653ef63aa137189dcafeb82e4dd40579b3fbb6a5a2a1eb5f2d484",
            payloadSizeBytes = 79_209_842L,
            requiredPaths = setOf(
                "bin/jadx",
                "bin/apktool",
                "lib/jadx/bin/jadx",
                "lib/apktool/apktool.jar",
            ),
            commands = mapOf(
                "jadx" to "bin/jadx",
                "apktool" to "bin/apktool",
            ),
            selfTests = mapOf(
                "java-version" to TrustedSelfTest(
                    title = "Java runtime",
                    command = "java -version",
                    expectedExitCodes = setOf(0),
                    outputContains = listOf("version"),
                ),
                "jadx-version" to TrustedSelfTest(
                    title = "JADX CLI",
                    command = "jadx --version",
                    expectedExitCodes = setOf(0),
                    outputContains = listOf("1.5.5"),
                ),
                "apktool-version" to TrustedSelfTest(
                    title = "Apktool",
                    command = "apktool --version",
                    expectedExitCodes = setOf(0),
                    outputContains = listOf("3.0.2"),
                ),
            ),
            sources = mapOf(
                "jadx" to TrustedSource(
                    version = "1.5.5",
                    url = "https://github.com/skylot/jadx/releases/download/v1.5.5/jadx-1.5.5.zip",
                    sha256 = "38a5766d3c8170c41566b4b13ea0ede2430e3008421af4927235c2880234d51a",
                ),
                "apktool" to TrustedSource(
                    version = "3.0.2",
                    url = "https://github.com/iBotPeaches/Apktool/releases/download/v3.0.2/apktool_3.0.2.jar",
                    sha256 = "eee4669a704a14e0623407e6701b0b91887e61e1e4049cb7a82833e14ae8b5fd",
                ),
            ),
        ),
        "elf-native-static" to TrustedToolpack(
            title = "ELF and native static analysis",
            version = "checksec-3.2.0_autocrack-1.0.0",
            architecture = "arm64",
            payloadSha256 = "4fe3c74c7af905a8586d8ec3cc8157f8e312aec9eef73708818429f5f6910983",
            payloadSizeBytes = 4_459_830L,
            requiredPaths = setOf(
                "bin/checksec",
                "bin/elf-deps",
                "bin/elf-report",
            ),
            commands = mapOf(
                "checksec" to "bin/checksec",
                "elf-deps" to "bin/elf-deps",
                "elf-report" to "bin/elf-report",
            ),
            selfTests = mapOf(
                "checksec-version" to TrustedSelfTest(
                    title = "Checksec ARM64",
                    command = "checksec --version",
                    expectedExitCodes = setOf(0),
                    outputContains = listOf("3.2.0"),
                ),
                "elf-deps-self-test" to TrustedSelfTest(
                    title = "ELF dependency reporter",
                    command = "elf-deps --self-test",
                    expectedExitCodes = setOf(0),
                    outputContains = listOf("ELF_DEPS_SELF_TEST_OK"),
                ),
                "elf-report-self-test" to TrustedSelfTest(
                    title = "ELF full report generator",
                    command = "elf-report --self-test",
                    expectedExitCodes = setOf(0),
                    outputContains = listOf("ELF_REPORT_SELF_TEST_OK"),
                ),
            ),
            sources = mapOf(
                "checksec" to TrustedSource(
                    version = "3.2.0",
                    url = "https://github.com/slimm609/checksec/releases/download/3.2.0/checksec_3.2.0_arm64.deb",
                    sha256 = "4834ac10b87a4faa143fdbf8cc7458be68dbeb9d2e2ec005b669ced7eae9615d",
                ),
            ),
        ),
        "rizin-deep-static" to TrustedToolpack(
            title = "Rizin deep ELF and native analysis",
            version = "rizin-0.9.1_autocrack-1.0.1",
            architecture = "arm64",
            payloadSha256 = "54d465c8fe84e6f5e5f8be0b56780633f28b2ead84453618fff282ddb50d84b1",
            payloadSizeBytes = 60_113_392L,
            requiredPaths = setOf(
                "bin/rizin",
                "bin/rz-functions",
                "bin/rz-disasm",
                "bin/rz-deep-report",
                "lib/rizin/rizin",
            ),
            commands = mapOf(
                "rizin" to "bin/rizin",
                "rz-functions" to "bin/rz-functions",
                "rz-disasm" to "bin/rz-disasm",
                "rz-deep-report" to "bin/rz-deep-report",
            ),
            selfTests = mapOf(
                "rizin-version" to TrustedSelfTest(
                    title = "Rizin ARM64",
                    command = "rizin -v",
                    expectedExitCodes = setOf(0),
                    outputContains = listOf("0.9.1"),
                ),
                "rz-functions-self-test" to TrustedSelfTest(
                    title = "Rizin function inventory",
                    command = "rz-functions --self-test",
                    expectedExitCodes = setOf(0),
                    outputContains = listOf("RZ_FUNCTIONS_SELF_TEST_OK"),
                ),
                "rz-disasm-self-test" to TrustedSelfTest(
                    title = "Rizin bounded disassembly",
                    command = "rz-disasm --self-test",
                    expectedExitCodes = setOf(0),
                    outputContains = listOf("RZ_DISASM_SELF_TEST_OK"),
                ),
                "rz-deep-report-self-test" to TrustedSelfTest(
                    title = "Rizin deep report generator",
                    command = "rz-deep-report --self-test",
                    expectedExitCodes = setOf(0),
                    outputContains = listOf("RZ_DEEP_REPORT_SELF_TEST_OK"),
                ),
            ),
            sources = mapOf(
                "rizin" to TrustedSource(
                    version = "0.9.1",
                    url = "https://github.com/rizinorg/rizin/releases/download/v0.9.1/rizin-v0.9.1-android-aarch64.tar.gz",
                    sha256 = "49b96162df17fb0eba443884f8eb0792145646d05c96ac6542e7776a0960fff2",
                ),
            ),
        ),
    )
}
