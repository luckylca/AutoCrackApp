package com.luckylca.autocrack.runtime

import org.junit.Assert.assertThrows
import org.junit.Test

class JnitraceToolpackTrustPolicyTest {
    @Test
    fun acceptsPinnedCompleteJnitraceToolpack() {
        BuiltInToolpackTrustPolicy.requireTrusted(manifest())
    }

    @Test
    fun rejectsJnitraceEnginePayloadMutation() {
        assertThrows(IllegalArgumentException::class.java) {
            BuiltInToolpackTrustPolicy.requireTrusted(
                manifest().copy(
                    payloadSha256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                ),
            )
        }
    }

    @Test
    fun rejectsMissingFridaRequirements() {
        assertThrows(IllegalArgumentException::class.java) {
            BuiltInToolpackTrustPolicy.requireTrusted(
                manifest().copy(requires = ToolpackRequirements()),
            )
        }
    }

    private fun manifest(): ToolpackPackageManifest = ToolpackPackageManifest(
        schemaVersion = 2,
        id = "jnitrace",
        title = "jnitrace complete JNI tracing client",
        version = "jnitrace-3.3.1_autocrack-1.0.1",
        architecture = "arm64",
        payloadEntry = "payload.zip",
        payloadSha256 = "92efc9377bbc3c2f52649675560908ec16573485e3df7641a15c42e9a61f35e3",
        payloadSizeBytes = 4_674_924L,
        requiredPaths = listOf(
            "bin/jnitrace",
            "python/jnitrace/jnitrace.py",
            "python/jnitrace/build/jnitrace.js",
            "python/jnitrace.egg-info/PKG-INFO",
            "python/hexdump.py",
            "SKILL.md",
            "VERSION",
            "AUTOCRACK_PATCH.md",
            "upstream-original/jnitrace/build/jnitrace.js",
        ),
        commands = listOf(ToolpackCommand("jnitrace", "bin/jnitrace")),
        selfTests = listOf(
            ToolpackSelfTest(
                id = "jnitrace-version",
                title = "Upstream jnitrace CLI and Frida Python integration",
                command = "jnitrace --version",
                expectedExitCodes = setOf(0),
                outputContains = listOf("jnitrace 3.3.1"),
            ),
            ToolpackSelfTest(
                id = "jnitrace-cli-surface",
                title = "Spawn attach remote filters and backtrace CLI surface",
                command = "jnitrace --help",
                expectedExitCodes = setOf(0),
                outputContains = listOf(
                    "--inject-method",
                    "--remote",
                    "--backtrace",
                    "--include",
                    "--exclude",
                    "--libraries",
                ),
            ),
            ToolpackSelfTest(
                id = "jnitrace-frida17-compat",
                title = "Frida 17 static Module API compatibility patch",
                command = "python3 -c \"from pathlib import Path; p=Path('/opt/autocrack/toolpacks/active/jnitrace/python/jnitrace/build/jnitrace.js'); s=p.read_text(); assert 'Module.findExportByName(' not in s; assert s.count('Module.findGlobalExportByName') >= 3; print('AUTOCRACK_JNITRACE_FRIDA17_OK')\"",
                expectedExitCodes = setOf(0),
                outputContains = listOf("AUTOCRACK_JNITRACE_FRIDA17_OK"),
            ),
            ToolpackSelfTest(
                id = "jnitrace-engine",
                title = "Upstream compiled JNI tracing engine",
                command = "test -s /opt/autocrack/toolpacks/active/jnitrace/python/jnitrace/build/jnitrace.js && printf 'AUTOCRACK_JNITRACE_ENGINE_OK\\n'",
                expectedExitCodes = setOf(0),
                outputContains = listOf("AUTOCRACK_JNITRACE_ENGINE_OK"),
            ),
        ),
        sources = listOf(
            ToolpackSourceArtifact("jnitrace-sdist", "3.3.1", "https://files.pythonhosted.org/packages/00/d9/25136bf8b76a99c8f93843f75771d2b19b29004d322b94bf565773120c8b/jnitrace-3.3.1.tar.gz", "6fc6b39a561b34415250ddcc8eaa54a8d9414ca4f42532e909506493d471efed"),
            ToolpackSourceArtifact("colorama-wheel", "0.4.6", "https://files.pythonhosted.org/packages/d1/d6/3965ed04c63042e047cb6a3e6ed1a63a35087b6a609aa3a15ed8ac56c221/colorama-0.4.6-py2.py3-none-any.whl", "4f1d9991f5acc0ca119f9d443620b77f9d6b33703e51011c16baf57afb285fc6"),
            ToolpackSourceArtifact("hexdump-sdist", "3.3", "https://files.pythonhosted.org/packages/55/b3/279b1d57fa3681725d0db8820405cdcb4e62a9239c205e4ceac4391c78e4/hexdump-3.3.zip", "d781a43b0c16ace3f9366aade73e8ad3a7bd5137d58f0b45ab2d3f54876f20db"),
            ToolpackSourceArtifact("setuptools-wheel", "80.9.0", "https://files.pythonhosted.org/packages/a3/dc/17031897dae0efacfea57dfd3a82fdd2a2aeb58e0ff71b77b87e44edc772/setuptools-80.9.0-py3-none-any.whl", "062d34222ad13e0cc312a4c02d73f059e86a4acbfbdea8f8f76b28c99f306922"),
        ),
        requires = ToolpackRequirements(commands = listOf("frida", "android-frida-server")),
    )
}
