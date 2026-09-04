package com.luckylca.autocrack.runtime

import org.junit.Assert.assertThrows
import org.junit.Test

class BlutterToolpackTrustPolicyTest {
    @Test
    fun acceptsPinnedCompleteBlutterToolpack() {
        BuiltInToolpackTrustPolicy.requireTrusted(manifest())
    }

    @Test
    fun rejectsBlutterPayloadMutation() {
        assertThrows(IllegalArgumentException::class.java) {
            BuiltInToolpackTrustPolicy.requireTrusted(
                manifest().copy(
                    payloadSha256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                ),
            )
        }
    }

    @Test
    fun rejectsMissingCompilerRequirement() {
        val original = manifest()
        assertThrows(IllegalArgumentException::class.java) {
            BuiltInToolpackTrustPolicy.requireTrusted(
                original.copy(
                    requires = original.requires.copy(
                        commands = original.requires.commands.filterNot { it == "clang++-16" },
                    ),
                ),
            )
        }
    }

    @Test
    fun rejectsBlutterSourceMutation() {
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
        id = "blutter",
        title = "Blutter complete Flutter Dart AOT analysis pipeline",
        version = "blutter-4a60ac648bf448c5a7596437243bcd0b9376fdf0_autocrack-1.0.0",
        architecture = "arm64",
        payloadEntry = "payload.zip",
        payloadSha256 = "fb49fce4572731b3be175cb2719e59c7ba757a3d89bc86547f6816988a36a791",
        payloadSizeBytes = 582_051L,
        requiredPaths = listOf(
            "bin/blutter",
            "upstream/blutter.py",
            "upstream/dartvm_fetch_build.py",
            "upstream/extract_dart_info.py",
            "upstream/blutter/CMakeLists.txt",
            "upstream/scripts/frida.template.js",
            "upstream/scripts/dartvm_create_srclist.py",
            "upstream/.autocrack-source-revision",
            "AUTOCRACK_PATCH.md",
            "SKILL.md",
            "VERSION",
        ),
        commands = listOf(
            ToolpackCommand("blutter", "bin/blutter"),
        ),
        selfTests = listOf(
            ToolpackSelfTest(
                id = "blutter-cli",
                title = "Complete upstream Blutter CLI",
                command = "blutter --help",
                expectedExitCodes = setOf(0),
                outputContains = listOf(
                    "Reversing a flutter application tool",
                    "--dart-version",
                    "--rebuild",
                    "--no-analysis",
                ),
            ),
            ToolpackSelfTest(
                id = "blutter-toolchain",
                title = "Linux ARM64 Blutter compiler and Python dependencies",
                command = "clang++-16 --version >/dev/null && cmake --version >/dev/null && ninja --version >/dev/null && pkg-config --exists capstone && PYTHONDONTWRITEBYTECODE=1 python3 -B -c \"import elftools,requests;print('AUTOCRACK_BLUTTER_TOOLCHAIN_OK')\"",
                expectedExitCodes = setOf(0),
                outputContains = listOf("AUTOCRACK_BLUTTER_TOOLCHAIN_OK"),
            ),
            ToolpackSelfTest(
                id = "blutter-source",
                title = "Pinned full upstream Blutter source and patch",
                command = "test -s /opt/autocrack/toolpacks/active/blutter/upstream/blutter.py && test -s /opt/autocrack/toolpacks/active/blutter/upstream/dartvm_fetch_build.py && test -s /opt/autocrack/toolpacks/active/blutter/upstream/scripts/frida.template.js && grep -F 'AutoCrack Debian ARM64 compatibility' /opt/autocrack/toolpacks/active/blutter/upstream/blutter/CMakeLists.txt && printf 'AUTOCRACK_BLUTTER_SOURCE_OK\\n'",
                expectedExitCodes = setOf(0),
                outputContains = listOf("AUTOCRACK_BLUTTER_SOURCE_OK"),
            ),
        ),
        sources = listOf(
            ToolpackSourceArtifact(
                name = "blutter-source",
                version = "4a60ac648bf448c5a7596437243bcd0b9376fdf0",
                url = "https://codeload.github.com/worawit/blutter/zip/4a60ac648bf448c5a7596437243bcd0b9376fdf0",
                sha256 = "f48e5a0d767dd5bb3dcd999afd45436c6de0f8b981a3cebe689750dc1a2af61f",
            ),
        ),
        requires = ToolpackRequirements(
            commands = listOf(
                "clang-16",
                "clang++-16",
                "cmake",
                "ninja",
                "pkg-config",
                "git",
                "python3",
            ),
        ),
    )
}
