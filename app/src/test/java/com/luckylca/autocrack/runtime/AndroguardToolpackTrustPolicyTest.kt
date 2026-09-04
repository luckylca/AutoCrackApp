package com.luckylca.autocrack.runtime

import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

import org.junit.Assert.assertThrows
import org.junit.Test

class AndroguardToolpackTrustPolicyTest {
    @Test
    fun acceptsPinnedCompleteAndroguardToolpack() {
        BuiltInToolpackTrustPolicy.requireTrusted(manifest())
    }

    @Test
    fun rejectsAndroguardWheelhouseLockMutation() {
        val original = manifest()
        assertThrows(IllegalArgumentException::class.java) {
            BuiltInToolpackTrustPolicy.requireTrusted(
                original.copy(
                    sources = original.sources.map { source ->
                        if (source.name == "wheelhouse-lock") {
                            source.copy(
                                sha256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                            )
                        } else {
                            source
                        }
                    },
                ),
            )
        }
    }

    private fun manifest(): ToolpackPackageManifest = ToolpackPackageManifest(
        schemaVersion = 2,
        id = "androguard",
        title = "Androguard complete Android static analysis API and CLI",
        version = "androguard-4.1.4_autocrack-1.0.0",
        architecture = "arm64",
        payloadEntry = "payload.zip",
        payloadSha256 = "be941c731d37770f77a62f957db4a8ed65fb28283dfa58ece5c082852124bbc0",
        payloadSizeBytes = 90_753_037L,
        requiredPaths = listOf(
            "bin/androguard",
            "python/androguard/__init__.py",
            "python/androguard/cli/cli.py",
            "WHEELHOUSE.lock.json",
            "SKILL.md",
        ),
        commands = listOf(ToolpackCommand("androguard", "bin/androguard")),
        selfTests = listOf(
            ToolpackSelfTest(
                id = "androguard-cli",
                title = "Complete upstream Androguard CLI",
                command = "androguard --help",
                expectedExitCodes = setOf(0),
                outputContains = listOf("Usage:", "androguard"),
            ),
            ToolpackSelfTest(
                id = "androguard-python-api",
                title = "Androguard high-level and resource Python APIs",
                command = "PYTHONDONTWRITEBYTECODE=1 python3 -B -c \"import androguard;from androguard.misc import AnalyzeAPK;from androguard.core.apk import APK;from androguard.core.axml import AXMLPrinter,ARSCParser;print(androguard.__version__);print('AUTOCRACK_ANDROGUARD_API_OK')\"",
                expectedExitCodes = setOf(0),
                outputContains = listOf("4.1.4", "AUTOCRACK_ANDROGUARD_API_OK"),
            ),
        ),
        sources = lockedSources(),
        requires = ToolpackRequirements(),
    )

    private fun lockedSources(): List<ToolpackSourceArtifact> {
        val lock = findProjectFile("toolpacks/androguard/wheelhouse.lock.json")
        val lockText = lock.readText(Charsets.UTF_8)
        val lockJson = JSONObject(lockText)
        val wheels = lockJson.getJSONArray("wheels")
        val sources = buildList {
            for (index in 0 until wheels.length()) {
                val wheel = wheels.getJSONObject(index)
                add(
                    ToolpackSourceArtifact(
                        name = "wheel-" + wheel.getString("name").replace("_", "-").lowercase(),
                        version = wheel.getString("version"),
                        url = wheel.getString("url"),
                        sha256 = wheel.getString("sha256"),
                    ),
                )
            }
            add(
                ToolpackSourceArtifact(
                    name = "wheelhouse-lock",
                    version = "androguard-4.1.4_autocrack-1.0.0",
                    url = "https://github.com/luckylca/AutoCrackApp/blob/main/toolpacks/androguard/wheelhouse.lock.json",
                    sha256 = sha256(lockText),
                ),
            )
        }
        return sources
    }

    private fun findProjectFile(relativePath: String): File {
        var current = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        repeat(8) {
            val candidate = File(current, relativePath)
            if (candidate.isFile) return candidate
            current = current.parentFile ?: return@repeat
        }
        error("project file not found: " + relativePath)
    }

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
