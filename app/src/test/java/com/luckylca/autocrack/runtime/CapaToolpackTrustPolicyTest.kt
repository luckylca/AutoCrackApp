package com.luckylca.autocrack.runtime

import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertThrows
import org.junit.Test

class CapaToolpackTrustPolicyTest {
    @Test
    fun acceptsPinnedCompleteCapaToolpack() {
        BuiltInToolpackTrustPolicy.requireTrusted(manifest())
    }

    @Test
    fun rejectsCapaPayloadMutation() {
        assertThrows(IllegalArgumentException::class.java) {
            BuiltInToolpackTrustPolicy.requireTrusted(
                manifest().copy(
                    payloadSha256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                ),
            )
        }
    }

    @Test
    fun rejectsCapaRuleSourceMutation() {
        val original = manifest()
        assertThrows(IllegalArgumentException::class.java) {
            BuiltInToolpackTrustPolicy.requireTrusted(
                original.copy(
                    sources = original.sources.map { source ->
                        if (source.name == "capa-rules-source") {
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

    @Test
    fun rejectsMissingEmbeddedSignaturePath() {
        val original = manifest()
        assertThrows(IllegalArgumentException::class.java) {
            BuiltInToolpackTrustPolicy.requireTrusted(
                original.copy(
                    requiredPaths = original.requiredPaths.filterNot {
                        it == "python/sigs/3_flare_common_libs.sig"
                    },
                ),
            )
        }
    }

    private fun manifest(): ToolpackPackageManifest = ToolpackPackageManifest(
        schemaVersion = 2,
        id = "capa",
        title = "capa complete ARM64 capability analysis API and rules",
        version = "capa-9.4.0_rules-9.4.0_autocrack-1.0.0",
        architecture = "arm64",
        payloadEntry = "payload.zip",
        payloadSha256 = "4940a6e91294aa8d76e00e0db67c27a5c5179313ceaee45b4ff822d5a74685a2",
        payloadSizeBytes = 105_462_978L,
        requiredPaths = listOf(
            "bin/capa",
            "python/capa/__init__.py",
            "python/capa/main.py",
            "python/capa/loader.py",
            "python/capa/engine.py",
            "python/capa/rules/__init__.py",
            "python/capa/render/json.py",
            "python/rules/anti-analysis/anti-av/block-operations-on-executable-memory-pages-using-arbitrary-code-guard.yml",
            "python/rules/nursery",
            "python/sigs/1_flare_msvc_rtf_32_64.sig",
            "python/sigs/2_flare_msvc_atlmfc_32_64.sig",
            "python/sigs/3_flare_common_libs.sig",
            "WHEELHOUSE.lock.json",
            "SKILL.md",
            "VERSION",
        ),
        commands = listOf(
            ToolpackCommand("capa", "bin/capa"),
        ),
        selfTests = listOf(
            ToolpackSelfTest(
                id = "capa-version",
                title = "Upstream capa CLI version",
                command = "capa --version",
                expectedExitCodes = setOf(0),
                outputContains = listOf("capa 9.4.0"),
            ),
            ToolpackSelfTest(
                id = "capa-cli",
                title = "Complete upstream capa CLI",
                command = "capa --help",
                expectedExitCodes = setOf(0),
                outputContains = listOf("--json", "--rules", "--signatures"),
            ),
            ToolpackSelfTest(
                id = "capa-python-api",
                title = "Full Python API and embedded rule/signature resources",
                command = "PYTHONDONTWRITEBYTECODE=1 python3 -B -c \"import pathlib,capa,capa.main,capa.loader,capa.rules,capa.engine,capa.render.json;root=pathlib.Path(capa.__file__).resolve().parent.parent;assert len(list((root/'rules').rglob('*.yml')))==1042;assert len(list((root/'sigs').glob('*.sig')))==3;capa.rules.get_rules([root/'rules']);assert len(capa.main.get_default_signatures())==3;print('AUTOCRACK_CAPA_API_RULES_OK')\"",
                expectedExitCodes = setOf(0),
                outputContains = listOf("AUTOCRACK_CAPA_API_RULES_OK"),
            ),
        ),
        sources = lockedSources(),
        requires = ToolpackRequirements(),
    )

    private fun lockedSources(): List<ToolpackSourceArtifact> {
        val lock = findProjectFile("toolpacks/capa/wheelhouse.lock.json")
        val lockText = lock.readText(Charsets.UTF_8)
        val lockJson = JSONObject(lockText)
        val wheels = lockJson.getJSONArray("wheels")
        return buildList {
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
                    name = "flare-capa-sdist-signatures",
                    version = "9.4.0",
                    url = "https://files.pythonhosted.org/packages/source/f/flare-capa/flare_capa-9.4.0.tar.gz",
                    sha256 = "c4f421abac566e23657241e4ddc66119beb0caf5f082ee42d68b7c879ebb7fc6",
                ),
            )
            add(
                ToolpackSourceArtifact(
                    name = "capa-rules-source",
                    version = "2af9fbfc1c9b4634dbeb76b5d34fca9389fa7f80",
                    url = "https://codeload.github.com/mandiant/capa-rules/zip/2af9fbfc1c9b4634dbeb76b5d34fca9389fa7f80",
                    sha256 = "2b3408c0ef9313683cfe2b7dab6c3fb8c2ac3fa8bb0c95281341d220dfc5e1ca",
                ),
            )
            add(
                ToolpackSourceArtifact(
                    name = "wheelhouse-lock",
                    version = "capa-9.4.0_rules-9.4.0_autocrack-1.0.0",
                    url = "https://github.com/luckylca/AutoCrackApp/blob/main/toolpacks/capa/wheelhouse.lock.json",
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
