package com.luckylca.autocrack.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal fun appendJsonLineWithRotation(
    file: File,
    jsonLine: String,
    maxBytes: Long,
    backupCount: Int,
) {
    require(maxBytes > 0L) { "maxBytes must be positive" }
    require(backupCount >= 0) { "backupCount must not be negative" }
    val line = jsonLine.trimEnd('\r', '\n') + "\n"
    val lineBytes = line.toByteArray(Charsets.UTF_8).size.toLong()
    file.parentFile?.mkdirs()
    if (file.isFile && file.length() + lineBytes > maxBytes) {
        rotateJsonlFiles(file, backupCount)
    }
    file.appendText(line, Charsets.UTF_8)
}

private fun rotateJsonlFiles(file: File, backupCount: Int) {
    if (backupCount == 0) {
        Files.deleteIfExists(file.toPath())
        return
    }
    Files.deleteIfExists(File(file.parentFile, "${file.name}.$backupCount").toPath())
    for (index in backupCount - 1 downTo 1) {
        val source = File(file.parentFile, "${file.name}.$index")
        if (source.isFile) {
            Files.move(
                source.toPath(),
                File(file.parentFile, "${file.name}.${index + 1}").toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }
    if (file.isFile) {
        Files.move(
            file.toPath(),
            File(file.parentFile, "${file.name}.1").toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )
    }
}
