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
        "rootfs-pcap-analysis" to TrustedToolpack(
            title = "Rootfs pcap metadata analysis",
            version = "pcap-summary-1.0.0",
            architecture = "all",
            payloadSha256 = "cfa34e98e43c6665143acacbedd9b249cdfb0f81c76cccf516b17fd4cffaebe9",
            payloadSizeBytes = 11_703L,
            requiredPaths = setOf("bin/pcap-summary"),
            commands = mapOf(
                "pcap-summary" to "bin/pcap-summary",
            ),
            selfTests = mapOf(
                "pcap-summary-self-test" to TrustedSelfTest(
                    title = "Pure Python pcap summary helper",
                    command = "pcap-summary --self-test",
                    expectedExitCodes = setOf(0),
                    outputContains = listOf("PCAP_SUMMARY_SELF_TEST_OK"),
                ),
            ),
            sources = mapOf(
                "pcap-summary" to TrustedSource(
                    version = "1.0.0",
                    url = "https://github.com/luckylca/AutoCrackApp/tree/main/toolpacks/pcap-analysis",
                    sha256 = "5a182f44c7f8f9944e894f2869e5ec3d5312ff4db098a155fdd90f30dec78ad1",
                ),
            ),
        ),
        "apk-dex-static" to TrustedToolpack(
            title = "APK and DEX static analysis",
            version = "jadx-1.5.6_apktool-3.0.3",
            architecture = "all",
            payloadSha256 = "7235f0ab51a59a2232551df2427517a8a6096ba8efb325658ae04a6bf66d0df8",
            payloadSizeBytes = 93_753_109L,
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
                    outputContains = listOf("1.5.6"),
                ),
                "apktool-version" to TrustedSelfTest(
                    title = "Apktool",
                    command = "apktool --version",
                    expectedExitCodes = setOf(0),
                    outputContains = listOf("3.0.3"),
                ),
            ),
            sources = mapOf(
                "jadx" to TrustedSource(
                    version = "1.5.6",
                    url = "https://github.com/skylot/jadx/releases/download/v1.5.6/jadx-1.5.6.zip",
                    sha256 = "545ea2be9c242511bc145755cf4bda2485ade42966e096f8b4d3da2a230e8974",
                ),
                "apktool" to TrustedSource(
                    version = "3.0.3",
                    url = "https://github.com/iBotPeaches/Apktool/releases/download/v3.0.3/apktool_3.0.3.jar",
                    sha256 = "dbf930b076c6b9be08d57c449cacefc3bdd6b71ebd59b3066fc0e1f5b14f9423",
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
        "perfetto-analysis" to TrustedToolpack(
            title = "Perfetto trace analysis",
            version = "perfetto-58.2-autocrack-1.0.0",
            architecture = "arm64",
            payloadSha256 = "087425724070bd58fd41e35aa568ec3874ff8779420196efd2a273c91dfd3ef1",
            payloadSizeBytes = 14_086_296L,
            requiredPaths = setOf("bin/trace_processor"),
            commands = mapOf(
                "trace_processor" to "bin/trace_processor",
            ),
            selfTests = mapOf(
                "trace-processor-help" to TrustedSelfTest(
                    title = "Perfetto trace_processor ARM64 CLI",
                    command = "trace_processor --help >/dev/null",
                    expectedExitCodes = setOf(0),
                    outputContains = emptyList(),
                ),
            ),
            sources = mapOf(
                "perfetto-linux-arm64" to TrustedSource(
                    version = "58.2",
                    url = "https://github.com/google/perfetto/releases/download/v58.2/linux-arm64.zip",
                    sha256 = "a82bf4111a340a7ea8577bcfd62e014e8e81b9e6a35a3190f5415fb800051ab0",
                ),
            ),
        ),
        "android-frida" to TrustedToolpack(
            title = "Android Frida bounded dynamic instrumentation",
            version = "frida-17.17.0-autocrack-1.0.3",
            architecture = "arm64",
            payloadSha256 = "67664cbef3a5b4b77f75b2e85102c53589ea9c475b520bda4c64c08949a0468d",
            payloadSizeBytes = 124_957_234L,
            requiredPaths = setOf(
                "bin/frida-server-android",
                "bin/frida-autocrack-client",
                "libexec/autocrack-frida-agent.js",
                "libexec/frida_autocrack_client.py",
            ),
            commands = mapOf(
                "frida-server-android" to "bin/frida-server-android",
                "frida-autocrack-client" to "bin/frida-autocrack-client",
            ),
            selfTests = mapOf(
                "frida-server-android-binary" to TrustedSelfTest(
                    title = "Official Android ARM64 Frida server payload",
                    command = "test -x /opt/autocrack/toolpacks/packs/android-frida/frida-17.17.0-autocrack-1.0.3/bin/frida-server-android && printf \"AUTOCRACK_FRIDA_SERVER_BINARY_OK\\n\"",
                    expectedExitCodes = setOf(0),
                    outputContains = listOf("AUTOCRACK_FRIDA_SERVER_BINARY_OK"),
                ),
                "frida-python-import" to TrustedSelfTest(
                    title = "ARM64 Frida Python binding import",
                    command = "PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=/opt/autocrack/toolpacks/packs/android-frida/frida-17.17.0-autocrack-1.0.3/python python3 -B -c \"import frida; print(frida.__version__)\"",
                    expectedExitCodes = setOf(0),
                    outputContains = listOf("17.17.0"),
                ),
                "frida-bounded-client-help" to TrustedSelfTest(
                    title = "Bounded AutoCrack Frida client",
                    command = "/opt/autocrack/toolpacks/packs/android-frida/frida-17.17.0-autocrack-1.0.3/bin/frida-autocrack-client --help",
                    expectedExitCodes = setOf(0),
                    outputContains = listOf("native-trace", "tls-trace"),
                ),
            ),
            sources = mapOf(
                "frida-server-android-arm64" to TrustedSource(
                    version = "17.17.0",
                    url = "https://github.com/frida/frida/releases/download/17.17.0/frida-server-17.17.0-android-arm64.xz",
                    sha256 = "09d1fad867b27d69562a79289f4c412e85867f5d38ab72877036ed35e4223021",
                ),
                "frida-python-aarch64" to TrustedSource(
                    version = "17.17.0",
                    url = "https://pypi.org/project/frida/17.17.0/",
                    sha256 = "82ddfa720588a0429fd3dd8e75ccf5c722d57da3d5544d1ba420741c032ba7a8",
                ),
                "frida-java-bridge" to TrustedSource(
                    version = "7.0.13",
                    url = "https://registry.npmjs.org/frida-java-bridge/-/frida-java-bridge-7.0.13.tgz",
                    sha256 = "0ae4e5393b5bf237ba7cfe23666248a7e6884cc9321ac0a77f073bcb30d951c0",
                ),
            ),
        ),
        "android-lldb-server" to TrustedToolpack(
            title = "Android LLDB server",
            version = "android-llvm-r522817_autocrack-1.3.0-seize-runtime-stop",
            architecture = "arm64",
            payloadSha256 = "f2d3b3925ffc49419508dd97cd657d4a8a2e0b0b313f473105173b96ce31b899",
            payloadSizeBytes = 28_396_656L,
            requiredPaths = setOf("bin/lldb-server-android"),
            commands = mapOf(
                "lldb-server-android" to "bin/lldb-server-android",
            ),
            selfTests = mapOf(
                "lldb-server-android-binary" to TrustedSelfTest(
                    title = "Android LLDB server payload",
                    command = "test -x /opt/autocrack/toolpacks/packs/android-lldb-server/android-llvm-r522817_autocrack-1.3.0-seize-runtime-stop/bin/lldb-server-android && printf \"AUTOCRACK_LLDB_ANDROID_BINARY_OK\\n\"",
                    expectedExitCodes = setOf(0),
                    outputContains = listOf("AUTOCRACK_LLDB_ANDROID_BINARY_OK"),
                ),
            ),
            sources = mapOf(
                "lldb-server" to TrustedSource(
                    version = "android-llvm-r522817-autocrack-seize-runtime-stop",
                    url = "https://android.googlesource.com/toolchain/llvm-project/+/d8003a456d14a3deb8054cdaa529ffbf02d9b262",
                    sha256 = "71d9ed6a90776d7dbdbcb315ea2171a763c071e5a370ec1b8b0c28157af41b20",
                ),
            ),
        ),
    )
}
