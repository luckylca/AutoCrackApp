package com.luckylca.autocrack.analysis

import com.luckylca.autocrack.apk.ApkArtifactKind
import com.luckylca.autocrack.apk.ExtractedApk
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApkArchiveInspectorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun inspect_readsManifestDexResourcesAndAarch64Elf() {
        val apk = temporaryFolder.newFile("base.apk")
        writeZip(
            apk,
            mapOf(
                "AndroidManifest.xml" to byteArrayOf(1, 2, 3),
                "resources.arsc" to byteArrayOf(4, 5),
                "res/drawable/icon.png" to byteArrayOf(6),
                "assets/config.json" to "{}".toByteArray(),
                "META-INF/CERT.RSA" to byteArrayOf(7),
                "classes.dex" to dexHeader("035"),
                "classes2.dex" to dexHeader("039"),
                "lib/arm64-v8a/libdemo.so" to aarch64ElfHeader(),
            ),
        )

        val summary = ApkArchiveInspector().inspect(extractedApk(apk))

        assertEquals(8, summary.entryCount)
        assertTrue(summary.manifestEntryPresent)
        assertTrue(summary.resourcesArscPresent)
        assertEquals(1, summary.resourceEntryCount)
        assertEquals(1, summary.assetEntryCount)
        assertEquals(1, summary.signingEntryCount)
        assertEquals(listOf("035", "039"), summary.dexFiles.map(DexFileSummary::dexVersion))
        assertTrue(summary.dexFiles.all(DexFileSummary::validMagic))
        assertEquals(1, summary.nativeLibraries.size)
        assertEquals("arm64-v8a", summary.nativeLibraries.single().abi)
        assertEquals("ELF64", summary.nativeLibraries.single().elfClass)
        assertEquals("AArch64", summary.nativeLibraries.single().machine)
        assertTrue(summary.nativeLibraries.single().validElfMagic)
        assertEquals(listOf("arm64-v8a"), summary.abis)
        assertTrue(summary.warnings.isEmpty())
    }

    @Test
    fun inspect_reportsUnsafePathAndInvalidBinaryMagic() {
        val apk = temporaryFolder.newFile("unsafe.apk")
        writeZip(
            apk,
            mapOf(
                "AndroidManifest.xml" to byteArrayOf(1),
                "classes.dex" to "not-dex".toByteArray(),
                "lib/armeabi-v7a/libbad.so" to "not-elf".toByteArray(),
                "../outside.txt" to byteArrayOf(9),
            ),
        )

        val summary = ApkArchiveInspector().inspect(extractedApk(apk))

        assertFalse(summary.dexFiles.single().validMagic)
        assertFalse(summary.nativeLibraries.single().validElfMagic)
        assertTrue(summary.warnings.any { it.contains("可疑路径") })
        assertTrue(summary.warnings.any { it.contains("DEX 魔数无效") })
        assertTrue(summary.warnings.any { it.contains("有效 ELF") })
    }

    @Test
    fun inspect_rejectsNonZipFile() {
        val apk = temporaryFolder.newFile("broken.apk")
        apk.writeText("not a zip")

        assertThrows(StaticAnalysisException::class.java) {
            ApkArchiveInspector().inspect(extractedApk(apk))
        }
    }

    private fun extractedApk(file: File): ExtractedApk = ExtractedApk(
        sourcePath = "/data/app/example/base.apk",
        localPath = file.canonicalPath,
        fileName = file.name,
        kind = ApkArtifactKind.BASE,
        sizeBytes = file.length(),
        sha256 = "test-sha256",
    )

    private fun writeZip(file: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(file.outputStream()).use { output ->
            entries.forEach { (name, bytes) ->
                output.putNextEntry(ZipEntry(name))
                output.write(bytes)
                output.closeEntry()
            }
        }
    }

    private fun dexHeader(version: String): ByteArray =
        "dex\n${version}\u0000".toByteArray(Charsets.US_ASCII)

    private fun aarch64ElfHeader(): ByteArray = ByteArray(20).apply {
        this[0] = 0x7f
        this[1] = 'E'.code.toByte()
        this[2] = 'L'.code.toByte()
        this[3] = 'F'.code.toByte()
        this[4] = 2
        this[5] = 1
        this[18] = 0xb7.toByte()
        this[19] = 0
    }
}
