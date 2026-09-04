package com.luckylca.autocrack.runtime

import org.junit.Assert.assertThrows
import org.junit.Test

class FridaIl2CppBridgeToolpackTrustPolicyTest {
    @Test
    fun acceptsPinnedCompleteFridaIl2CppBridgeToolpack() {
        BuiltInToolpackTrustPolicy.requireTrusted(manifest())
    }

    @Test
    fun rejectsIl2CppPayloadMutation() {
        assertThrows(IllegalArgumentException::class.java) {
            BuiltInToolpackTrustPolicy.requireTrusted(
                manifest().copy(
                    payloadSha256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                ),
            )
        }
    }

    @Test
    fun rejectsMissingFridaRequirement() {
        val original = manifest()
        assertThrows(IllegalArgumentException::class.java) {
            BuiltInToolpackTrustPolicy.requireTrusted(
                original.copy(
                    requires = original.requires.copy(
                        commands = listOf("android-frida-server"),
                    ),
                ),
            )
        }
    }

    @Test
    fun rejectsIl2CppNpmSourceMutation() {
        val original = manifest()
        assertThrows(IllegalArgumentException::class.java) {
            BuiltInToolpackTrustPolicy.requireTrusted(
                original.copy(
                    sources = original.sources.map { source ->
                        source.copy(
                            sha256 = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                        )
                    },
                ),
            )
        }
    }

    private fun manifest(): ToolpackPackageManifest = ToolpackPackageManifest(
        schemaVersion = 2,
        id = "frida-il2cpp-bridge",
        title = "frida-il2cpp-bridge complete IL2CPP runtime toolkit",
        version = "frida-il2cpp-bridge-0.13.2_autocrack-1.0.0",
        architecture = "arm64",
        payloadEntry = "payload.zip",
        payloadSha256 = "7ed0b4ffdcbcd99dd7cc9281e4bcb611b7c67e0d183032e6ba6b424610029359",
        payloadSizeBytes = 770_897L,
        requiredPaths = listOf(
            "bin/frida-il2cpp-bridge",
            "package.json",
            "dist/index.js",
            "dist/index.js.map",
            "dist/index.d.ts",
            "cli/main.py",
            "cli/src/app.py",
            "cli/src/dump/agent.js",
            "upstream-original/package.json",
            "upstream-original/dist/index.js",
            "upstream-original/cli/main.py",
            "AUTOCRACK_PATCH.md",
            "SKILL.md",
            "VERSION",
        ),
        commands = listOf(
            ToolpackCommand("frida-il2cpp-bridge", "bin/frida-il2cpp-bridge"),
        ),
        selfTests = listOf(
            ToolpackSelfTest(
                id = "frida-il2cpp-cli",
                title = "Upstream IL2CPP bridge CLI",
                command = "frida-il2cpp-bridge --help",
                expectedExitCodes = setOf(0),
                outputContains = listOf("IL2CPP options", "dump"),
            ),
            ToolpackSelfTest(
                id = "frida-il2cpp-version",
                title = "Upstream bridge and Frida version reporting",
                command = "frida-il2cpp-bridge --version",
                expectedExitCodes = setOf(0),
                outputContains = listOf("frida-il2cpp-bridge", "0.13.2"),
            ),
            ToolpackSelfTest(
                id = "frida-il2cpp-library",
                title = "Complete compiled Il2Cpp runtime library surface",
                command = "grep -F 'globalThis.Il2Cpp = Il2Cpp' /opt/autocrack/toolpacks/active/frida-il2cpp-bridge/dist/index.js >/dev/null && grep -F 'function perform' /opt/autocrack/toolpacks/active/frida-il2cpp-bridge/dist/index.d.ts >/dev/null && grep -F 'function trace' /opt/autocrack/toolpacks/active/frida-il2cpp-bridge/dist/index.d.ts >/dev/null && printf 'AUTOCRACK_IL2CPP_LIBRARY_OK\\n'",
                expectedExitCodes = setOf(0),
                outputContains = listOf("AUTOCRACK_IL2CPP_LIBRARY_OK"),
            ),
            ToolpackSelfTest(
                id = "frida-il2cpp-python311",
                title = "Patched upstream CLI imports on rootfs Python 3.11",
                command = "PYTHONDONTWRITEBYTECODE=1 python3 -B -c \"import sys;sys.path[:0]=['/opt/autocrack/toolpacks/active/frida-il2cpp-bridge/cli','/opt/autocrack/toolpacks/active/android-frida/python'];import src.app,src.dump.command;print('AUTOCRACK_IL2CPP_PY311_OK')\"",
                expectedExitCodes = setOf(0),
                outputContains = listOf("AUTOCRACK_IL2CPP_PY311_OK"),
            ),
        ),
        sources = listOf(
            ToolpackSourceArtifact(
                name = "frida-il2cpp-bridge-npm",
                version = "0.13.2",
                url = "https://registry.npmjs.org/frida-il2cpp-bridge/-/frida-il2cpp-bridge-0.13.2.tgz",
                sha256 = "298430a57a9d713feedf2b26bd0495becf2823240429e6408545c86381ac8060",
            ),
        ),
        requires = ToolpackRequirements(
            commands = listOf("frida", "android-frida-server"),
        ),
    )
}
