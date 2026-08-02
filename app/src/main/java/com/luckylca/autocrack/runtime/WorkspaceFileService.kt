package com.luckylca.autocrack.runtime

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WorkspaceFileService(
    private val workspaceRoot: File,
) {
    init {
        if (!workspaceRoot.exists()) {
            check(workspaceRoot.mkdirs()) { "无法创建工作区：${workspaceRoot.path}" }
        }
        require(workspaceRoot.isDirectory) { "工作区不是目录：${workspaceRoot.path}" }
    }

    suspend fun list(relativePath: String = "."): List<WorkspaceFileEntry> =
        withContext(Dispatchers.IO) {
            val directory = resolve(relativePath)
            require(directory.isDirectory) { "目标不是目录：$relativePath" }
            directory.listFiles()
                .orEmpty()
                .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
                .map { file -> file.toEntry() }
        }

    suspend fun stat(relativePath: String): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val file = resolve(relativePath)
        require(file.exists()) { "文件不存在：$relativePath" }
        file.toEntry()
    }

    suspend fun readText(relativePath: String, maxChars: Int = DEFAULT_MAX_TEXT_CHARS): String =
        withContext(Dispatchers.IO) {
            require(maxChars in 1..MAX_TEXT_CHARS) { "读取字符上限超出范围" }
            val file = resolve(relativePath)
            require(file.isFile) { "目标不是文件：$relativePath" }
            file.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(maxChars + 1)
                val count = reader.read(buffer)
                when {
                    count < 0 -> ""
                    count <= maxChars -> String(buffer, 0, count)
                    else -> String(buffer, 0, maxChars) + "\n...[file preview truncated]"
                }
            }
        }

    suspend fun writeText(
        relativePath: String,
        content: String,
        append: Boolean = false,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        require(content.length <= MAX_WRITE_CHARS) { "单次写入内容过大" }
        val file = resolve(relativePath)
        file.parentFile?.let { parent ->
            if (!parent.exists()) check(parent.mkdirs()) { "无法创建目录：${parent.path}" }
        }
        if (append) {
            file.appendText(content, Charsets.UTF_8)
        } else {
            file.writeText(content, Charsets.UTF_8)
        }
        file.toEntry()
    }

    suspend fun mkdir(relativePath: String): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val directory = resolve(relativePath)
        if (!directory.exists()) check(directory.mkdirs()) { "无法创建目录：$relativePath" }
        require(directory.isDirectory) { "目标不是目录：$relativePath" }
        directory.toEntry()
    }

    suspend fun copy(sourceRelativePath: String, destinationRelativePath: String): WorkspaceFileEntry =
        withContext(Dispatchers.IO) {
            val source = resolve(sourceRelativePath)
            val destination = resolve(destinationRelativePath)
            require(source.exists()) { "源文件不存在：$sourceRelativePath" }
            destination.parentFile?.let { parent ->
                if (!parent.exists()) check(parent.mkdirs()) { "无法创建目录：${parent.path}" }
            }
            if (source.isDirectory) {
                source.copyRecursively(destination, overwrite = true)
            } else {
                source.copyTo(destination, overwrite = true)
            }
            destination.toEntry()
        }

    suspend fun move(sourceRelativePath: String, destinationRelativePath: String): WorkspaceFileEntry =
        withContext(Dispatchers.IO) {
            val source = resolve(sourceRelativePath)
            val destination = resolve(destinationRelativePath)
            require(source.exists()) { "源文件不存在：$sourceRelativePath" }
            destination.parentFile?.let { parent ->
                if (!parent.exists()) check(parent.mkdirs()) { "无法创建目录：${parent.path}" }
            }
            if (!source.renameTo(destination)) {
                if (source.isDirectory) {
                    source.copyRecursively(destination, overwrite = true)
                    source.deleteRecursively()
                } else {
                    source.copyTo(destination, overwrite = true)
                    check(source.delete()) { "移动后无法删除源文件：$sourceRelativePath" }
                }
            }
            destination.toEntry()
        }

    suspend fun delete(relativePath: String): Boolean = withContext(Dispatchers.IO) {
        val target = resolve(relativePath)
        require(target != workspaceRoot.canonicalFile) { "拒绝删除工作区根目录" }
        !target.exists() || if (target.isDirectory) target.deleteRecursively() else target.delete()
    }

    suspend fun sha256(relativePath: String): String = withContext(Dispatchers.IO) {
        val file = resolve(relativePath)
        require(file.isFile) { "目标不是文件：$relativePath" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    fun rootPath(): String = workspaceRoot.canonicalPath

    internal fun resolve(relativePath: String): File {
        require(relativePath.isNotBlank()) { "路径不能为空" }
        val candidate = File(workspaceRoot, relativePath).canonicalFile
        val root = workspaceRoot.canonicalFile
        val rootPath = root.path
        require(candidate.path == rootPath || candidate.path.startsWith("$rootPath${File.separator}")) {
            "工作区路径越界：$relativePath"
        }
        return candidate
    }

    private fun File.toEntry(): WorkspaceFileEntry = WorkspaceFileEntry(
        relativePath = relativeTo(workspaceRoot.canonicalFile).path.ifBlank { "." },
        name = name,
        directory = isDirectory,
        sizeBytes = if (isFile) length() else 0L,
        lastModifiedEpochMillis = lastModified(),
    )

    companion object {
        const val DEFAULT_MAX_TEXT_CHARS = 200_000
        const val MAX_TEXT_CHARS = 1_000_000
        const val MAX_WRITE_CHARS = 1_000_000
    }
}

data class WorkspaceFileEntry(
    val relativePath: String,
    val name: String,
    val directory: Boolean,
    val sizeBytes: Long,
    val lastModifiedEpochMillis: Long,
)
