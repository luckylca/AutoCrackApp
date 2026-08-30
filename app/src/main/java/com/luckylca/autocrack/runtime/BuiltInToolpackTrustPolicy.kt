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
            version = "jadx-1.5.6_apktool-3.0.3_autocrack-1.0.1",
            architecture = "all",
            payloadSha256 = "2071081447bc2765760eb436c08819d4a3a2c8853785451bbc1f4516a6a89109",
            payloadSizeBytes = 87_888_327L,
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
            title = "Android Frida dynamic instrumentation",
            version = "frida-17.17.0-autocrack-1.1.0",
            architecture = "arm64",
            payloadSha256 = "b117c3b0e2c72f431270de1025e2f406f204fd7a7039f3520a0706d549ede15f",
            payloadSizeBytes = 147_139_984L,
            requiredPaths = setOf(
                "bin/frida-server-android",
                "bin/frida-autocrack-client",
                "libexec/autocrack-frida-agent.js",
                "libexec/frida_autocrack_client.py",
                "libexec/frida_tools_launcher.py",
                "bin/frida",
                "bin/frida-ps",
                "bin/frida-trace",
            ),
            commands = mapOf(
                "frida-server-android" to "bin/frida-server-android",
                "frida-autocrack-client" to "bin/frida-autocrack-client",
                "frida" to "bin/frida",
                "frida-ls-devices" to "bin/frida-ls-devices",
                "frida-ps" to "bin/frida-ps",
                "frida-kill" to "bin/frida-kill",
                "frida-ls" to "bin/frida-ls",
                "frida-rm" to "bin/frida-rm",
                "frida-pull" to "bin/frida-pull",
                "frida-push" to "bin/frida-push",
                "frida-discover" to "bin/frida-discover",
                "frida-trace" to "bin/frida-trace",
                "frida-strace" to "bin/frida-strace",
                "frida-itrace" to "bin/frida-itrace",
                "frida-join" to "bin/frida-join",
                "frida-create" to "bin/frida-create",
                "frida-compile" to "bin/frida-compile",
                "frida-pm" to "bin/frida-pm",
                "frida-apk" to "bin/frida-apk",
            ),
            selfTests = mapOf(
                "frida-server-android-binary" to TrustedSelfTest(
                    title = "Official Android ARM64 Frida server payload",
                    command = "test -x /opt/autocrack/toolpacks/packs/android-frida/frida-17.17.0-autocrack-1.1.0/bin/frida-server-android && printf \"AUTOCRACK_FRIDA_SERVER_BINARY_OK\\n\"",
                    expectedExitCodes = setOf(0),
                    outputContains = listOf("AUTOCRACK_FRIDA_SERVER_BINARY_OK"),
                ),
                "frida-python-import" to TrustedSelfTest(
                    title = "ARM64 Frida Python binding import",
                    command = "PYTHONDONTWRITEBYTECODE=1 python3 -B -c \"import frida; print(frida.__version__)\"",
                    expectedExitCodes = setOf(0),
                    outputContains = listOf("17.17.0"),
                ),
                "frida-upstream-cli-version" to TrustedSelfTest(
                    title = "Upstream Frida CLI",
                    command = "frida --version",
                    expectedExitCodes = setOf(0),
                    outputContains = listOf("17.17.0"),
                ),
                "frida-autocrack-client-help" to TrustedSelfTest(
                    title = "Optional AutoCrack Frida helper",
                    command = "/opt/autocrack/toolpacks/packs/android-frida/frida-17.17.0-autocrack-1.1.0/bin/frida-autocrack-client --help",
                    expectedExitCodes = setOf(0),
                    outputContains = listOf("native-trace", "tls-trace", "java-field-write"),
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
                "frida-tools" to TrustedSource(
                    version = "14.10.4",
                    url = "https://pypi.org/project/frida-tools/14.10.4/",
                    sha256 = "7a2c544b545d095040fffbd3768a287a426343dad89095b4a24f4b20382d926a",
                ),
                "colorama" to TrustedSource(
                    version = "0.4.6",
                    url = "https://pypi.org/project/colorama/0.4.6/",
                    sha256 = "4f1d9991f5acc0ca119f9d443620b77f9d6b33703e51011c16baf57afb285fc6",
                ),
                "prompt-toolkit" to TrustedSource(
                    version = "3.0.53",
                    url = "https://pypi.org/project/prompt-toolkit/3.0.53/",
                    sha256 = "01c0891d7f9237d5e339f7d3e42cdae80b7534abb1c7c0e3352efba6231492f2",
                ),
                "pygments" to TrustedSource(
                    version = "2.21.0",
                    url = "https://pypi.org/project/Pygments/2.21.0/",
                    sha256 = "2363c69b61c4a97c838da3b130dcd6468f4848992b21a82f2a63ec34377137d9",
                ),
                "wcwidth" to TrustedSource(
                    version = "0.8.3",
                    url = "https://pypi.org/project/wcwidth/0.8.3/",
                    sha256 = "d5b73dba6158a595ec9370350e7f2637bcac8d6c5e4fde34f30fcffb6103a5e4",
                ),
                "websockets" to TrustedSource(
                    version = "13.1",
                    url = "https://pypi.org/project/websockets/13.1/",
                    sha256 = "308e20f22c2c77f3f39caca508e765f8725020b84aa963474e18c59accbf4c02",
                ),
                "frida-java-bridge" to TrustedSource(
                    version = "7.0.13",
                    url = "https://registry.npmjs.org/frida-java-bridge/-/frida-java-bridge-7.0.13.tgz",
                    sha256 = "0ae4e5393b5bf237ba7cfe23666248a7e6884cc9321ac0a77f073bcb30d951c0",
                ),
            ),
        ),
        "android-host-shell" to TrustedToolpack(
            title = "Android host root shell bridge",
            version = "android-host-shell-1.0.2",
            architecture = "all",
            payloadSha256 = "7c1dc787ba197627b42cd4592ca62c62f029750efa1a0f4d9579ce781df7963c",
            payloadSizeBytes = 6_857L,
            requiredPaths = setOf(
                "bin/android-shell",
                "SKILL.md",
            ),
            commands = mapOf(
                "android-shell" to "bin/android-shell",
            ),
            selfTests = mapOf(
                "android-host-shell-client-self-test" to TrustedSelfTest(
                    title = "Android host shell bridge client",
                    command = "/opt/autocrack/toolpacks/packs/android-host-shell/android-host-shell-1.0.2/bin/android-shell --self-test",
                    expectedExitCodes = setOf(0),
                    outputContains = listOf("ANDROID_HOST_SHELL_CLIENT_OK"),
                ),
            ),
            sources = mapOf(
                "android-host-shell-client" to TrustedSource(
                    version = "1.0.1",
                    url = "https://github.com/luckylca/AutoCrackApp/blob/main/toolpacks/android-host-shell/bin/android-shell",
                    sha256 = "3b6f046ab9f9ec7e2db10be03e926f017ebeff142a98b7a5023d55d0bc18a22b",
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
