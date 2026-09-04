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
        val schemaVersion: Int = 1,
        val requires: ToolpackRequirements = ToolpackRequirements(),
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

        require(manifest.schemaVersion == trusted.schemaVersion) {
            "工具包 manifest schema 与内置信任目录不一致"
        }
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
        require(manifest.requires == trusted.requires) {
            "工具包运行时需求与内置信任目录不一致"
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
            payloadSha256 = "695b6b569af3acea7b5ad64e35fb029db8dcf42771e73ff21d72dfed209d8437",
            payloadSizeBytes = 12_540L,
            requiredPaths = setOf("bin/pcap-summary", "SKILL.md"),
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
        "android-pcap-helper" to TrustedToolpack(
            title = "Android host tcpdump",
            version = "tcpdump-4.99.5_libpcap-1.10.5_autocrack-1.1.0",
            architecture = "arm64",
            payloadSha256 = "78fd0429a1cc422a6b9c17fb685d39ba069e3605d1c0db0b3e5ad49e08b976a2",
            payloadSizeBytes = 3_946_116L,
            requiredPaths = setOf(
                "bin/tcpdump",
                "host-bin/tcpdump",
                "SKILL.md",
            ),
            commands = mapOf(
                "tcpdump" to "bin/tcpdump",
            ),
            selfTests = mapOf(
                "tcpdump-binary" to TrustedSelfTest(
                    title = "Android tcpdump binary",
                    command = "test -x /opt/autocrack/toolpacks/active/android-pcap-helper/host-bin/tcpdump && printf 'AUTOCRACK_TCPDUMP_BINARY_OK\n'",
                    expectedExitCodes = setOf(0),
                    outputContains = listOf("AUTOCRACK_TCPDUMP_BINARY_OK"),
                ),
                "tcpdump-launcher" to TrustedSelfTest(
                    title = "Standard tcpdump Android-host launcher",
                    command = "test -x /opt/autocrack/toolpacks/active/android-pcap-helper/bin/tcpdump && grep -F '\"$@\"' /opt/autocrack/toolpacks/active/android-pcap-helper/bin/tcpdump",
                    expectedExitCodes = setOf(0),
                    outputContains = listOf("$@"),
                ),
            ),
            sources = mapOf(
                "tcpdump" to TrustedSource(
                    version = "4.99.5",
                    url = "https://www.tcpdump.org/release/tcpdump-4.99.5.tar.xz",
                    sha256 = "d76395ab82d659d526291b013eee200201380930793531515abfc6e77b4f2ee5",
                ),
                "libpcap" to TrustedSource(
                    version = "1.10.5",
                    url = "https://www.tcpdump.org/release/libpcap-1.10.5.tar.xz",
                    sha256 = "84fa89ac6d303028c1c5b754abff77224f45eca0a94eb1a34ff0aa9ceece3925",
                ),
            ),
        ),
        "apk-dex-static" to TrustedToolpack(
            title = "APK and DEX static analysis",
            version = "jadx-1.5.6_apktool-3.0.3_autocrack-1.0.1",
            architecture = "all",
            payloadSha256 = "b81d5ccf6d9730ab7864ad6380696a88f1e7aa7f97a7f34bd578fdb9572187b9",
            payloadSizeBytes = 93_755_288L,
            requiredPaths = setOf(
                "bin/jadx",
                "bin/apktool",
                "lib/jadx/bin/jadx",
                "lib/apktool/apktool.jar",
                "SKILL.md",
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
        "lief-static" to TrustedToolpack(
            title = "LIEF full Python API and ELF report helper",
            version = "lief-1.0.0-autocrack-1.0.0",
            architecture = "arm64",
            payloadSha256 = "b2bb3a3a2fae53782bd1bc3bc357504bd7a1747dc782a2942944510b3fa34e78",
            payloadSizeBytes = 12_246_513L,
            requiredPaths = setOf(
                "bin/lief-elf-report",
                "libexec/lief_elf_report.py",
                "python/lief/__init__.py",
                "SKILL.md",
            ),
            commands = mapOf(
                "lief-elf-report" to "bin/lief-elf-report",
            ),
            selfTests = mapOf(
                "lief-python-import" to TrustedSelfTest(
                    title = "Complete LIEF Python API import",
                    command = "PYTHONDONTWRITEBYTECODE=1 python3 -B -c \"import lief; print(lief.__version__)\"",
                    expectedExitCodes = setOf(0),
                    outputContains = listOf("1.0.0"),
                ),
                "lief-static-self-test" to TrustedSelfTest(
                    title = "Optional bounded ELF report helper",
                    command = "lief-elf-report --self-test",
                    expectedExitCodes = setOf(0),
                    outputContains = listOf("LIEF_STATIC_SELF_TEST_OK version=1.0.0"),
                ),
            ),
            sources = mapOf(
                "lief-python-aarch64" to TrustedSource(
                    version = "1.0.0",
                    url = "https://files.pythonhosted.org/packages/6f/21/097f7f28157870491d648c65befd3a66163eb42f23bd12d1bbda59e94c5e/lief-1.0.0-cp311-cp311-manylinux_2_28_aarch64.whl",
                    sha256 = "95fb7dc84960068ab881bc681e646cab55f3d736c07bb07e91d5cbc8738885a7",
                ),
            ),
        ),
        "elf-native-static" to TrustedToolpack(
            title = "ELF and native static analysis",
            version = "checksec-3.2.0_autocrack-1.0.0",
            architecture = "arm64",
            payloadSha256 = "9c92996c255866bfcd5fdd1567ad103e685fcfb4fe1f5206b9c32f5a8d7e201b",
            payloadSizeBytes = 4_460_638L,
            requiredPaths = setOf(
                "bin/checksec",
                "bin/elf-deps",
                "bin/elf-report",
                "SKILL.md",
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
            payloadSha256 = "be88eeb4918c2a6fe07a59e17e3190113fb0f534349dfa57b4a72644aa72ea39",
            payloadSizeBytes = 60_114_281L,
            requiredPaths = setOf(
                "bin/rizin",
                "bin/rz-functions",
                "bin/rz-disasm",
                "bin/rz-deep-report",
                "lib/rizin/rizin",
                "SKILL.md",
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
            payloadSha256 = "25271c9f340962e9364c9abba00c1e57ee5c2a4b12ca551c327d640564f19a4b",
            payloadSizeBytes = 14_087_073L,
            requiredPaths = setOf("bin/trace_processor", "SKILL.md"),
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
            payloadSha256 = "c756804f9f0841057fa4698e524299ed8c9d3c61f1d1db26be30b10a34ad04fa",
            payloadSizeBytes = 147_147_144L,
            requiredPaths = setOf(
                "bin/frida-server-android",
                "bin/android-frida-server",
                "bin/frida-autocrack-client",
                "libexec/autocrack-frida-agent.js",
                "libexec/frida_autocrack_client.py",
                "libexec/frida_tools_launcher.py",
                "bin/frida",
                "bin/frida-ps",
                "bin/frida-trace",
                "SKILL.md",
            ),
            commands = mapOf(
                "android-frida-server" to "bin/android-frida-server",
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
                    command = "test -x /opt/autocrack/toolpacks/active/android-frida/bin/frida-server-android && printf \"AUTOCRACK_FRIDA_SERVER_BINARY_OK\\n\"",
                    expectedExitCodes = setOf(0),
                    outputContains = listOf("AUTOCRACK_FRIDA_SERVER_BINARY_OK"),
                ),
                "android-frida-server-help" to TrustedSelfTest(
                    title = "Android Frida server lifecycle helper",
                    command = "android-frida-server 2>&1 || test $? -eq 2",
                    expectedExitCodes = setOf(0),
                    outputContains = listOf("start|status|stop"),
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
                    command = "/opt/autocrack/toolpacks/active/android-frida/bin/frida-autocrack-client --help",
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
            payloadSha256 = "bcda8001725375043d098ff40bd3ea0fbdec68b1feb13e1a39f8e494bfec644e",
            payloadSizeBytes = 7_267L,
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
                    command = "/opt/autocrack/toolpacks/active/android-host-shell/bin/android-shell --self-test",
                    expectedExitCodes = setOf(0),
                    outputContains = listOf("ANDROID_HOST_SHELL_CLIENT_OK"),
                ),
            ),
            sources = mapOf(
                "android-host-shell-client" to TrustedSource(
                    version = "1.0.2",
                    url = "https://github.com/luckylca/AutoCrackApp/blob/main/toolpacks/android-host-shell/bin/android-shell",
                    sha256 = "68df06b3b46449de85a0491beb057e775ea34507ae303308d594f2df8f057898",
                ),
            ),
        ),
        "ui-inspect" to TrustedToolpack(
            title = "AutoCrack UI Inspect",
            version = "ui-inspect-1.0.0",
            architecture = "all",
            payloadSha256 = "b0d2b390a049537b84532bca827832816fc0d3a779b2e6ddb4c28b76ba584183",
            payloadSizeBytes = 19_712L,
            requiredPaths = setOf(
                "bin/ui-inspect",
                "libexec/ui_inspect_cli.py",
                "libexec/autocrack_runtime_client.py",
                "SKILL.md",
                "README.md",
                "VERSION",
            ),
            commands = mapOf(
                "ui-inspect" to "bin/ui-inspect",
            ),
            selfTests = mapOf(
                "ui-inspect-help" to TrustedSelfTest(
                    title = "AutoCrack UI Inspect CLI surface",
                    command = "/opt/autocrack/toolpacks/active/ui-inspect/bin/ui-inspect --help",
                    expectedExitCodes = setOf(0),
                    outputContains = listOf("windows", "listeners", "compose-tree"),
                ),
            ),
            sources = mapOf(
                "ui-inspect-cli" to TrustedSource(
                    version = "1.0.0",
                    url = "https://github.com/luckylca/AutoCrackApp/tree/main/toolpacks/ui-inspect",
                    sha256 = "154c49926a344787b4a62f2731c80f457ed11b9d1d9e3a47a9a67dfc456b4421",
                ),
            ),
            schemaVersion = 2,
            requires = ToolpackRequirements(
                runtime = ">=1.0.0",
                capabilities = listOf(
                    "ui.windows",
                    "ui.tree",
                    "ui.at",
                    "ui.find",
                    "ui.props",
                    "ui.parent",
                    "ui.children",
                    "ui.siblings",
                    "ui.listeners",
                    "ui.stack",
                    "ui.image",
                    "ui.action",
                    "ui.compose.status",
                    "ui.compose.tree",
                    "object.describe",
                ),
                commands = listOf(
                    "android-shell",
                ),
                optionalCapabilities = listOf(
                    "ui.compose.tree",
                ),
            ),
        ),
        "runtime-inspect" to TrustedToolpack(
            title = "AutoCrack Runtime Inspect",
            version = "runtime-inspect-1.0.0",
            architecture = "all",
            payloadSha256 = "2540d2e2e42bd1f2cbd1c52a1cd06f853032e021a06fccbb40903854bd3e8a37",
            payloadSizeBytes = 18_049L,
            requiredPaths = setOf(
                "bin/runtime-inspect",
                "libexec/runtime_inspect_cli.py",
                "libexec/autocrack_runtime_client.py",
                "SKILL.md",
                "README.md",
                "VERSION",
            ),
            commands = mapOf(
                "runtime-inspect" to "bin/runtime-inspect",
            ),
            selfTests = mapOf(
                "runtime-inspect-help" to TrustedSelfTest(
                    title = "AutoCrack Runtime Inspect CLI surface",
                    command = "/opt/autocrack/toolpacks/active/runtime-inspect/bin/runtime-inspect --help",
                    expectedExitCodes = setOf(0),
                    outputContains = listOf("doctor", "class-search", "object-dump", "activities"),
                ),
            ),
            sources = mapOf(
                "runtime-inspect-cli" to TrustedSource(
                    version = "1.0.0",
                    url = "https://github.com/luckylca/AutoCrackApp/tree/main/toolpacks/runtime-inspect",
                    sha256 = "25de19b43a015c6def3c7b6c507ce61e3694697225e2bcd45836e071e438991c",
                ),
            ),
            schemaVersion = 2,
            requires = ToolpackRequirements(
                runtime = ">=1.0.0",
                capabilities = listOf(
                    "runtime.process",
                    "runtime.doctor",
                    "runtime.activities",
                    "runtime.declared_activities",
                    "runtime.classloaders",
                    "runtime.class.search",
                    "runtime.class.describe",
                    "object.describe",
                    "object.fields",
                    "object.dump",
                    "object.release",
                ),
                commands = listOf(
                    "android-shell",
                ),
                optionalCapabilities = listOf(
                    "object.pin",
                    "object.clear_session",
                ),
            ),
        ),
        "memory-dump" to TrustedToolpack(
            title = "AutoCrack Memory Dump",
            version = "memory-dump-1.0.0",
            architecture = "all",
            payloadSha256 = "85bef17c35ffd209b9b49243baad449f477730cd379f41072b57636056bf73e6",
            payloadSizeBytes = 51_936L,
            requiredPaths = setOf(
                "bin/memory-dump",
                "libexec/memory_dump_cli.py",
                "libexec/autocrack_runtime_client.py",
                "SKILL.md",
                "README.md",
                "VERSION",
            ),
            commands = mapOf(
                "memory-dump" to "bin/memory-dump",
            ),
            selfTests = mapOf(
                "memory-dump-help" to TrustedSelfTest(
                    title = "AutoCrack Memory Dump CLI surface",
                    command = "/opt/autocrack/toolpacks/active/memory-dump/bin/memory-dump --help",
                    expectedExitCodes = setOf(0),
                    outputContains = listOf("maps", "dex-art-dump", "dex-art-export", "dex-dump", "assets-list"),
                ),
            ),
            sources = mapOf(
                "memory-dump-cli" to TrustedSource(
                    version = "1.0.0",
                    url = "https://github.com/luckylca/AutoCrackApp/tree/main/toolpacks/memory-dump",
                    sha256 = "6da8bcead05edd0f3facc89a82b6c2a2f817a4cf53ba5e3aac5a03ee2274efcf",
                ),
            ),
            schemaVersion = 2,
            requires = ToolpackRequirements(
                runtime = ">=1.0.0",
                capabilities = listOf(
                    "memory.maps",
                    "memory.modules",
                    "memory.native.modules",
                    "memory.read",
                    "memory.native.probe",
                    "memory.dladdr",
                    "memory.module.dump",
                    "memory.module.file_dump",
                    "memory.elf.info",
                    "memory.elf.symbols",
                    "memory.elf.relocations",
                    "memory.elf.dynamic",
                    "memory.dex.list",
                    "memory.dex.art_probe",
                    "memory.dex.art_pointer_probe",
                    "memory.dex.art_dump",
                    "memory.dex.art_export.open",
                    "memory.dex.art_export.chunk",
                    "memory.dex.art_export.close",
                    "memory.dex.info",
                    "memory.dex.apk_index",
                    "memory.dex.strings",
                    "memory.dex.classes",
                    "memory.dex.fields",
                    "memory.dex.methods",
                    "memory.dex.class_data",
                    "memory.dex.scan",
                    "memory.dex.dump",
                    "memory.assets.list",
                    "memory.assets.pull",
                    "memory.xml.pull",
                    "memory.xml.block_probe",
                    "memory.xml.binary",
                    "memory.xml.axml_decode",
                    "memory.xml.axml_text",
                    "memory.apk.entries",
                    "memory.apk.pull",
                ),
                commands = listOf(
                    "android-shell",
                ),
                optionalCapabilities = listOf(
                    "memory.xml.block_probe",
                    "memory.xml.binary",
                ),
            ),
        ),
        "runtime-control" to TrustedToolpack(
            title = "AutoCrack Runtime Control",
            version = "runtime-control-1.0.0",
            architecture = "all",
            payloadSha256 = "0d988732a70c560f4b6a055991f7f8b3baedc2a85c71cf1eab798c8477bfbdac",
            payloadSizeBytes = 26_039L,
            requiredPaths = setOf(
                "bin/runtime-control",
                "libexec/runtime_control_cli.py",
                "libexec/autocrack_runtime_client.py",
                "SKILL.md",
                "README.md",
                "VERSION",
            ),
            commands = mapOf(
                "runtime-control" to "bin/runtime-control",
            ),
            selfTests = mapOf(
                "runtime-control-help" to TrustedSelfTest(
                    title = "AutoCrack Runtime Control CLI surface",
                    command = "/opt/autocrack/toolpacks/active/runtime-control/bin/runtime-control --help",
                    expectedExitCodes = setOf(0),
                    outputContains = listOf("webview-debug", "webview-devtools-sockets", "secure-diagnose", "secure-disable", "so-diagnose", "activity-start"),
                ),
            ),
            sources = mapOf(
                "runtime-control-cli" to TrustedSource(
                    version = "1.0.0",
                    url = "https://github.com/luckylca/AutoCrackApp/tree/main/toolpacks/runtime-control",
                    sha256 = "48f2b7cf5bbb4a86f4255280d8881a0ea1efc39a6346fc9fe5171d5240fbae12",
                ),
            ),
            schemaVersion = 2,
            requires = ToolpackRequirements(
                runtime = ">=1.0.0",
                capabilities = listOf(
                    "control.activity.start",
                    "control.process.kill",
                    "control.so.inject",
                    "control.so.diagnose",
                    "control.so.dlopen",
                    "control.so.android_dlopen_ext",
                    "control.so.dlsym",
                    "control.secure.status",
                    "control.secure.diagnose",
                    "control.secure.disable",
                    "control.object.field.set",
                    "control.object.method.call",
                    "webview.list",
                    "webview.info",
                    "webview.debug",
                    "webview.devtools_socket",
                    "webview.eval",
                    "webview.clear_cache",
                    "webview.go_forward",
                    "webview.go_back",
                    "webview.reload",
                    "webview.load_url",
                    "webview.eval.result",
                ),
                commands = listOf(
                    "android-shell",
                ),
                optionalCapabilities = listOf(
                    "control.so.dlopen_namespace",
                ),
            ),
        ),
        "simplehook" to TrustedToolpack(
            title = "SimpleHook Android Java method debugger",
            version = "simplehook-0.1.1",
            architecture = "all",
            payloadSha256 = "28adb59057472533286bd091f7a53abf68915015dd471cd289bc887febfd384c",
            payloadSizeBytes = 44_254L,
            requiredPaths = setOf(
                "bin/simplehook",
                "libexec/simplehook_cli.py",
                "schema/simplehook-rule-v1.schema.json",
                "SKILL.md",
                "README.md",
                "VERSION",
            ),
            commands = mapOf(
                "simplehook" to "bin/simplehook",
            ),
            selfTests = mapOf(
                "simplehook-help" to TrustedSelfTest(
                    title = "SimpleHook CLI command surface",
                    command = "/opt/autocrack/toolpacks/active/simplehook/bin/simplehook --help",
                    expectedExitCodes = setOf(0),
                    outputContains = listOf("rules", "inspect", "environment"),
                ),
                "simplehook-schema-validation" to TrustedSelfTest(
                    title = "SimpleHook v1 example rule validation",
                    command = "SIMPLEHOOK_HOME=/tmp/simplehook-self-test /opt/autocrack/toolpacks/active/simplehook/bin/simplehook rules validate /opt/autocrack/toolpacks/active/simplehook/examples/replace-return-int.json --json",
                    expectedExitCodes = setOf(0),
                    outputContains = listOf("\"valid\":true"),
                ),
            ),
            sources = mapOf(
                "simplehook-cli" to TrustedSource(
                    version = "0.1.1",
                    url = "https://github.com/luckylca/AutoCrackApp/tree/main/toolpacks/simplehook",
                    sha256 = "af22a087912fd465b10d5f5bdef651ce38ced92341f40d47b60d2771cb2ed88e",
                ),
            ),
            schemaVersion = 2,
            requires = ToolpackRequirements(
                runtime = ">=1.0.0",
                capabilities = listOf("hook.reload", "hook.inspect"),
                commands = listOf("android-shell"),
                optionalCapabilities = listOf(
                    "runtime.process",
                    "runtime.class.search",
                    "runtime.class.describe",
                ),
            ),
        ),
        "android-lldb-server" to TrustedToolpack(
            title = "Standard LLDB client and Android server",
            version = "android-llvm-r522817_lldb-14_autocrack-2.0.0",
            architecture = "arm64",
            payloadSha256 = "639f4d94159807a2105852dc7cea8eb696469ce2f592c3adc7ca95827fe3770b",
            payloadSizeBytes = 283_871_844L,
            requiredPaths = setOf(
                "bin/lldb",
                "bin/android-lldb-server",
                "host-bin/lldb-server-android",
                "lib/llvm-14/bin/lldb",
                "lib/llvm-14/lib/python3.11/dist-packages/six.py",
                "SKILL.md",
            ),
            commands = mapOf(
                "lldb" to "bin/lldb",
                "android-lldb-server" to "bin/android-lldb-server",
            ),
            selfTests = mapOf(
                "lldb-server-android-binary" to TrustedSelfTest(
                    title = "Android LLDB server payload",
                    command = "test -x /opt/autocrack/toolpacks/active/android-lldb-server/host-bin/lldb-server-android && printf 'AUTOCRACK_LLDB_ANDROID_BINARY_OK\n'",
                    expectedExitCodes = setOf(0),
                    outputContains = listOf("AUTOCRACK_LLDB_ANDROID_BINARY_OK"),
                ),
                "lldb-client-version" to TrustedSelfTest(
                    title = "Standard Debian LLDB client",
                    command = "lldb --version",
                    expectedExitCodes = setOf(0),
                    outputContains = listOf("lldb version 14.0.6"),
                ),
                "lldb-python-runtime" to TrustedSelfTest(
                    title = "LLDB Python runtime",
                    command = "lldb --batch -o 'script import lldb, six; print(\"AUTOCRACK_LLDB_PYTHON_OK\", six.__version__)'",
                    expectedExitCodes = setOf(0),
                    outputContains = listOf("AUTOCRACK_LLDB_PYTHON_OK 1.16.0"),
                ),
            ),
            sources = mapOf(
                "lldb-server" to TrustedSource(
                    version = "android-llvm-r522817-autocrack-seize-runtime-stop",
                    url = "https://android.googlesource.com/toolchain/llvm-project/+/d8003a456d14a3deb8054cdaa529ffbf02d9b262",
                    sha256 = "71d9ed6a90776d7dbdbcb315ea2171a763c071e5a370ec1b8b0c28157af41b20",
                ),
                "debian-lldb-14-arm64" to TrustedSource(
                    version = "1:14.0.6-12",
                    url = "https://deb.debian.org/debian/pool/main/l/llvm-toolchain-14/lldb-14_14.0.6-12_arm64.deb",
                    sha256 = "b05d6bc6ba4ee60746fa1cc2af0c763a79c61cce4c3c6471521dabff8c088551",
                ),
                "debian-python3-lldb-14-arm64" to TrustedSource(
                    version = "1:14.0.6-12",
                    url = "https://deb.debian.org/debian/pool/main/l/llvm-toolchain-14/python3-lldb-14_14.0.6-12_arm64.deb",
                    sha256 = "1b0c76c86c52568513f07dcf9412ac038ef7b88a4755c2b1aa667a8b02f4377a",
                ),
                "debian-liblldb-14-arm64" to TrustedSource(
                    version = "1:14.0.6-12",
                    url = "https://deb.debian.org/debian/pool/main/l/llvm-toolchain-14/liblldb-14_14.0.6-12_arm64.deb",
                    sha256 = "acdaa8e8c06b7ee643aec4326b96b45d76d26e3112bd08cadce0c7a1f54de813",
                ),
                "debian-libclang-cpp14-arm64" to TrustedSource(
                    version = "1:14.0.6-12",
                    url = "https://deb.debian.org/debian/pool/main/l/llvm-toolchain-14/libclang-cpp14_14.0.6-12_arm64.deb",
                    sha256 = "dc983fc6aa0c1f7ef3f51aa3a2734ea6285ad5e7a283fe32d3239c85f718872d",
                ),
                "debian-libllvm14-arm64" to TrustedSource(
                    version = "1:14.0.6-12",
                    url = "https://deb.debian.org/debian/pool/main/l/llvm-toolchain-14/libllvm14_14.0.6-12_arm64.deb",
                    sha256 = "f22c3e843b12de66d642dceddc1db0de02934a4028dd60aecc4722f8bf04e6d6",
                ),
                "debian-python3-six" to TrustedSource(
                    version = "1.16.0-4",
                    url = "https://deb.debian.org/debian/pool/main/s/six/python3-six_1.16.0-4_all.deb",
                    sha256 = "fd189e9cecbcf17a1fc20aec30055c8afa9c1eec00cd6e7ab385087a2ab3b0d3",
                ),
            ),
        ),
    )
}
