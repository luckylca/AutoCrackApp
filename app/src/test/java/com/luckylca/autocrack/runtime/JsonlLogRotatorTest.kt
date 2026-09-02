package com.luckylca.autocrack.runtime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonlLogRotatorTest {
    @Test
    fun rotatesBeforeAppendingAndKeepsBoundedBackups() {
        val root = Files.createTempDirectory("jsonl-rotation").toFile()
        val log = File(root, "audit.jsonl")
        try {
            appendJsonLineWithRotation(log, "123456789", maxBytes = 10, backupCount = 2)
            appendJsonLineWithRotation(log, "next", maxBytes = 10, backupCount = 2)
            appendJsonLineWithRotation(log, "third", maxBytes = 10, backupCount = 2)
            appendJsonLineWithRotation(log, "fourth", maxBytes = 10, backupCount = 2)

            assertEquals("fourth\n", log.readText())
            assertEquals("third\n", File(root, "audit.jsonl.1").readText())
            assertEquals("next\n", File(root, "audit.jsonl.2").readText())
            assertFalse(File(root, "audit.jsonl.3").exists())
            assertTrue(listOf(log, File(root, "audit.jsonl.1"), File(root, "audit.jsonl.2")).all(File::isFile))
        } finally {
            root.deleteRecursively()
        }
    }
}
