package com.luckylca.autocrack.runtime

import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertThrows
import org.junit.Test

class Uiautomator2ToolpackTrustPolicyTest {
    @Test
    fun acceptsPinnedCompleteUiautomator2Toolpack() {
        BuiltInToolpackTrustPolicy.requireTrusted(manifest())
    }

    @Test
    fun rejectsUiautomator2PayloadMutation() {
        assertThrows(IllegalArgumentException::class.java) {
            BuiltInToolpackTrustPolicy.requireTrusted(
                manifest().copy(
                    payloadSha256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                ),
            )
        }
    }

    @Test
    fun rejectsMissingAdbRequirement() {
        assertThrows(IllegalArgumentException::class.java) {
            BuiltInToolpackTrustPolicy.requireTrusted(
                manifest().copy(requires = ToolpackRequirements()),
            )
        }
    }

    @Test
    fun rejectsUiautomator2DependencySourceMutation() {
        val original = manifest()
        assertThrows(IllegalArgumentException::class.java) {
            BuiltInToolpackTrustPolicy.requireTrusted(
                original.copy(
                    sources = original.sources.map { source ->
                        if (source.name == "adbutils-sdist") {
                            source.copy(
                                sha256 = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
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
        id = "uiautomator2",
        title = "uiautomator2 complete Android UI automation",
        version = "uiautomator2-3.7.0_adbutils-2.11.0_autocrack-1.0.0",
        architecture = "arm64",
        payloadEntry = "payload.zip",
        payloadSha256 = "8765ee4b3a1670985f803db05284ab92499a7797e2d64468b7cb0da94e0ff4ca",
        payloadSizeBytes = 40_688_700L,
        requiredPaths = listOf(
            "bin/uiautomator2",
            "bin/u2cli",
            "python/uiautomator2/__init__.py",
            "python/uiautomator2/__main__.py",
            "python/uiautomator2/agent_cli/__main__.py",
            "python/uiautomator2/assets/app-uiautomator.apk",
            "python/uiautomator2/assets/u2.jar",
            "python/uiautomator2/assets/version.json",
            "python/adbutils/__init__.py",
            "python/adbutils/_utils.py",
            "python/adbutils.egg-info/PKG-INFO",
            "WHEELHOUSE.lock.json",
            "SKILL.md",
            "VERSION",
        ),
        commands = listOf(
            ToolpackCommand("uiautomator2", "bin/uiautomator2"),
            ToolpackCommand("u2cli", "bin/u2cli"),
        ),
        selfTests = listOf(
            ToolpackSelfTest(
                id = "uiautomator2-version",
                title = "Upstream uiautomator2 CLI",
                command = "uiautomator2 version",
                expectedExitCodes = setOf(0),
                outputContains = listOf("uiautomator2 version: 3.7.0"),
            ),
            ToolpackSelfTest(
                id = "u2cli-help",
                title = "Upstream u2cli command group",
                command = "u2cli --help",
                expectedExitCodes = setOf(0),
                outputContains = listOf("Usage:", "u2cli"),
            ),
            ToolpackSelfTest(
                id = "uiautomator2-python-api",
                title = "Full Python API, adbutils and embedded device assets",
                command = "PYTHONDONTWRITEBYTECODE=1 python3 -B -c \"import importlib.metadata,pathlib,shutil,uiautomator2,adbutils;p=pathlib.Path(uiautomator2.__file__).parent/'assets';assert (p/'app-uiautomator.apk').is_file();assert (p/'u2.jar').is_file();assert shutil.which('adb');print(importlib.metadata.version('uiautomator2'));print(importlib.metadata.version('adbutils'));print('AUTOCRACK_UIAUTOMATOR2_API_OK')\"",
                expectedExitCodes = setOf(0),
                outputContains = listOf(
                    "3.7.0",
                    "2.11.0",
                    "AUTOCRACK_UIAUTOMATOR2_API_OK",
                ),
            ),
        ),
        sources = lockedSources(),
        requires = ToolpackRequirements(commands = listOf("adb")),
    )

    private fun lockedSources(): List<ToolpackSourceArtifact> {
        val lock = findProjectFile("toolpacks/uiautomator2/wheelhouse.lock.json")
        val lockText = lock.readText(Charsets.UTF_8)
        val lockJson = JSONObject(lockText)
        val wheels = lockJson.getJSONArray("wheels")
        return buildList {
            add(
                ToolpackSourceArtifact(
                    name = "uiautomator2-wheel",
                    version = "3.7.0",
                    url = "https://files.pythonhosted.org/packages/55/23/a5f93de8bb197ae2d2d0185c2c13d4b36ae7f18215e3e599e217f8e90e0d/uiautomator2-3.7.0-py3-none-any.whl",
                    sha256 = "731bf4e26e35cd440cd165b399b8a4d4b795178d78b9243769e336aee6dce985",
                ),
            )
            add(
                ToolpackSourceArtifact(
                    name = "adbutils-sdist",
                    version = "2.11.0",
                    url = "https://files.pythonhosted.org/packages/b6/80/c0cc9b47c8273f1f9c5f00577605f9c5903acf74cd80569fa1cb1072fc9f/adbutils-2.11.0.tar.gz",
                    sha256 = "7621182b219163bbfd16a240aa504c7834cf377d2bdc3f10452d5e8266fa7f87",
                ),
            )
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
                    version = "uiautomator2-3.7.0_adbutils-2.11.0_autocrack-1.0.0",
                    url = "https://github.com/luckylca/AutoCrackApp/blob/main/toolpacks/uiautomator2/wheelhouse.lock.json",
                    sha256 = sha256(lockText),
                ),
            )
        }
    }

    private fun findProjectFile(relativePath: String): File {
        var current = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        repeat(8) {
            val candidate = File(current, relativePath)
            if (candidate.isFile) return candidate
            current = current.parentFile ?: return@repeat
        }
        error("project file not found: $relativePath")
    }

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
