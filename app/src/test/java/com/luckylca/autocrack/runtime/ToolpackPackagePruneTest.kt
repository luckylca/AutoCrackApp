package com.luckylca.autocrack.runtime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolpackPackagePruneTest {
    @Test
    fun selectsOnlyUnreferencedZipArchives() {
        val root = Files.createTempDirectory("toolpack-packages").toFile()
        try {
            val current = File(root, "current.zip").apply { writeText("current") }
            val obsolete = File(root, "obsolete.ZIP").apply { writeText("obsolete") }
            File(root, "note.txt").writeText("keep")
            val directory = File(root, "directory.zip").apply { mkdirs() }

            val selected = unreferencedToolpackPackages(
                packageFiles = root.listFiles().orEmpty().toList(),
                referencedPackagePaths = setOf(current.path),
            )

            assertEquals(listOf(obsolete.canonicalFile), selected.map(File::getCanonicalFile))
            assertEquals(true, directory.isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }
}
