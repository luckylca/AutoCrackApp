package com.luckylca.autocrack.runtime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolpackPackageManifestTest {
    @Test
    fun parsesPinnedApkDexToolpackManifest() {
        val manifest = ToolpackPackageManifest.parse(validManifest())

        assertEquals("apk-dex-static", manifest.id)
        assertEquals("jadx-1.5.5_apktool-3.0.2", manifest.version)
        assertEquals("all", manifest.architecture)
        assertEquals(listOf("jadx", "apktool"), manifest.commands.map(ToolpackCommand::name))
        assertEquals(3, manifest.selfTests.size)
        assertEquals(2, manifest.sources.size)
        assertEquals(manifest.id, ToolpackPackageManifest.parse(manifest.toJson().toString()).id)
    }

    @Test
    fun rejectsTraversalInRequiredPath() {
        val invalid = validManifest().replace(
            "\"requiredPaths\": [\"bin/jadx\", \"bin/apktool\"]",
            "\"requiredPaths\": [\"../bin/jadx\"]",
        )

        assertThrows(IllegalArgumentException::class.java) {
            ToolpackPackageManifest.parse(invalid)
        }
    }

    @Test
    fun rejectsNonHttpsSource() {
        val invalid = validManifest().replace(
            "https://github.com/skylot/jadx/releases/download/v1.5.5/jadx-1.5.5.zip",
            "http://example.invalid/jadx.zip",
        )

        assertThrows(IllegalArgumentException::class.java) {
            ToolpackPackageManifest.parse(invalid)
        }
    }

    @Test
    fun resolvesOnlyInsideToolpackRoot() {
        val root = Files.createTempDirectory("toolpack-policy").toFile()
        try {
            val resolved = ToolpackPathPolicy.resolve(root, "lib/jadx/bin/jadx")
            assertTrue(resolved.path.startsWith(root.canonicalPath + File.separator))
            assertThrows(IllegalArgumentException::class.java) {
                ToolpackPathPolicy.resolve(root, "lib/../../escape")
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun validManifest(): String = """
        {
          "schemaVersion": 1,
          "id": "apk-dex-static",
          "title": "APK and DEX static analysis",
          "version": "jadx-1.5.5_apktool-3.0.2",
          "architecture": "all",
          "payloadEntry": "payload.zip",
          "payloadSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
          "payloadSizeBytes": 123456,
          "requiredPaths": ["bin/jadx", "bin/apktool"],
          "commands": [
            {"name": "jadx", "relativePath": "bin/jadx"},
            {"name": "apktool", "relativePath": "bin/apktool"}
          ],
          "selfTests": [
            {
              "id": "java-version",
              "title": "Java runtime",
              "command": "java -version",
              "expectedExitCodes": [0],
              "outputContains": ["version"]
            },
            {
              "id": "jadx-version",
              "title": "JADX CLI",
              "command": "jadx --version",
              "expectedExitCodes": [0],
              "outputContains": ["1.5.5"]
            },
            {
              "id": "apktool-version",
              "title": "Apktool",
              "command": "apktool --version",
              "expectedExitCodes": [0],
              "outputContains": ["3.0.2"]
            }
          ],
          "sources": [
            {
              "name": "jadx",
              "version": "1.5.5",
              "url": "https://github.com/skylot/jadx/releases/download/v1.5.5/jadx-1.5.5.zip",
              "sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
            },
            {
              "name": "apktool",
              "version": "3.0.2",
              "url": "https://github.com/iBotPeaches/Apktool/releases/download/v3.0.2/apktool_3.0.2.jar",
              "sha256": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
            }
          ]
        }
    """.trimIndent()
}
