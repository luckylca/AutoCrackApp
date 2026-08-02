package com.luckylca.autocrack.runtime

import android.content.Context
import android.net.Uri
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.json.JSONArray
import org.json.JSONObject

data class RootfsPackageManifest(
    val schemaVersion: Int,
    val id: String,
    val version: String,
    val architecture: String,
    val archiveEntry: String,
    val archiveSha256: String,
    val archiveSizeBytes: Long,
    val compression: String,
    val sourceImage: String?,
    val requiredPaths: List<String>,
) {
    init {
        require(schemaVersion == SUPPORTED_SCHEMA_VERSION) {
            "不支持的 rootfs manifest schema：$schemaVersion"
        }
        require(id.matches(ID_REGEX)) { "非法 rootfs id：$id" }
        require(version.isNotBlank()) { "rootfs version 不能为空" }
        require(architecture in SUPPORTED_ARCHITECTURES) {
            "当前仅支持 arm64/aarch64 rootfs，实际为 $architecture"
        }
        require(archiveEntry == "rootfs.tar.xz" || archiveEntry == "rootfs.tar.gz") {
            "不支持的 rootfs archiveEntry：$archiveEntry"
        }
        require(archiveSha256.matches(SHA256_REGEX)) { "rootfs SHA-256 格式非法" }
        require(archiveSizeBytes in 1..MAX_ARCHIVE_BYTES) { "rootfs 压缩包大小非法" }
        require(compression == "xz" || compression == "gzip") {
            "不支持的 rootfs 压缩格式：$compression"
        }
        require(requiredPaths.isNotEmpty()) { "rootfs requiredPaths 不能为空" }
        requiredPaths.forEach { path ->
            require(path.startsWith('/') && !path.contains("..")) { "非法必需路径：$path" }
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("schemaVersion", schemaVersion)
        .put("id", id)
        .put("version", version)
        .put("architecture", architecture)
        .put("archiveEntry", archiveEntry)
        .put("archiveSha256", archiveSha256)
        .put("archiveSizeBytes", archiveSizeBytes)
        .put("compression", compression)
        .put("sourceImage", sourceImage ?: JSONObject.NULL)
        .put("requiredPaths", JSONArray(requiredPaths))

    companion object {
        const val SUPPORTED_SCHEMA_VERSION = 1
        const val MAX_ARCHIVE_BYTES = 1_500_000_000L
        private val ID_REGEX = Regex("[A-Za-z0-9._-]{1,120}")
        private val SHA256_REGEX = Regex("[a-fA-F0-9]{64}")
        private val SUPPORTED_ARCHITECTURES = setOf("arm64", "aarch64")

        fun parse(text: String): RootfsPackageManifest {
            val json = JSONObject(text)
            val required = json.getJSONArray("requiredPaths")
            return RootfsPackageManifest(
                schemaVersion = json.getInt("schemaVersion"),
                id = json.getString("id"),
                version = json.getString("version"),
                architecture = json.getString("architecture").lowercase(Locale.US),
                archiveEntry = json.getString("archiveEntry"),
                archiveSha256 = json.getString("archiveSha256").lowercase(Locale.US),
                archiveSizeBytes = json.getLong("archiveSizeBytes"),
                compression = json.getString("compression").lowercase(Locale.US),
                sourceImage = json.optString("sourceImage").takeIf(String::isNotBlank),
                requiredPaths = buildList {
                    for (index in 0 until required.length()) add(required.getString(index))
                },
            )
        }
    }
}

data class RootfsInstallResult(
    val manifest: RootfsPackageManifest,
    val packagePath: String,
    val installedPath: String,
    val archiveBytes: Long,
    val extractedEntries: Int,
    val extractedBytes: Long,
    val durationMillis: Long,
)

object RootfsPathPolicy {
    fun resolveEntry(root: File, entryName: String): File {
        require(entryName.isNotBlank()) { "rootfs 条目名为空" }
        require(!entryName.startsWith('/')) { "rootfs 条目不能使用绝对路径：$entryName" }
        require('\u0000' !in entryName) { "rootfs 条目包含 NUL" }
        val target = File(root, entryName).canonicalFile
        val rootPath = root.canonicalPath
        require(target.path == rootPath || target.path.startsWith("$rootPath${File.separator}")) {
            "rootfs 条目路径越界：$entryName"
        }
        return target
    }
}

internal object RootfsHardLinkPolicy {
    fun shouldMaterialize(errno: Int): Boolean = when (errno) {
        OsConstants.EACCES,
        OsConstants.EPERM,
        OsConstants.EXDEV,
        OsConstants.EMLINK,
        OsConstants.ENOSYS,
        OsConstants.EROFS -> true

        else -> false
    }
}

private data class PendingHardLink(
    val target: File,
    val linkName: String,
    val mode: Int,
)

private data class HardLinkRestoreResult(
    val copiedBytes: Long,
    val materialized: Boolean,
)

class RootfsPackageInstaller(
    context: Context,
    private val layout: RuntimeLayout,
) {
    private val appContext = context.applicationContext

    suspend fun install(
        packageUri: Uri,
        onProgress: (String) -> Unit = {},
    ): RootfsInstallResult = withContext(Dispatchers.IO) {
        layout.initialize()
        val startedAt = System.currentTimeMillis()
        val packageFile = File(
            layout.rootfsPackagesRoot,
            "rootfs-package-${System.currentTimeMillis()}.zip",
        )
        val archiveFile = File(layout.tempRoot, "rootfs-${System.currentTimeMillis()}.archive")

        layout.updateRootfsState(RuntimeRootfsState.MANIFEST_READY)
        try {
            onProgress("正在复制 rootfs 包到应用私有目录")
            copyUriToFile(packageUri, packageFile)
            require(packageFile.length() in 1..MAX_PACKAGE_BYTES) {
                "rootfs 包大小非法：${packageFile.length()} B"
            }

            val manifest = ZipFile(packageFile).use { zip ->
                val manifestEntry = zip.getEntry(MANIFEST_ENTRY)
                    ?: error("rootfs 包缺少 $MANIFEST_ENTRY")
                val parsed = zip.getInputStream(manifestEntry).bufferedReader(Charsets.UTF_8).use {
                    RootfsPackageManifest.parse(it.readText())
                }
                val archiveEntry = zip.getEntry(parsed.archiveEntry)
                    ?: error("rootfs 包缺少 ${parsed.archiveEntry}")

                onProgress("正在校验 rootfs 压缩层 SHA-256")
                val digest = MessageDigest.getInstance("SHA-256")
                var copied = 0L
                zip.getInputStream(archiveEntry).use { input ->
                    FileOutputStream(archiveFile).buffered().use { output ->
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            copied += count
                            require(copied <= RootfsPackageManifest.MAX_ARCHIVE_BYTES) {
                                "rootfs 压缩层超过允许上限"
                            }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                }
                val actualSha = digest.digest().toHex()
                require(copied == parsed.archiveSizeBytes) {
                    "rootfs 压缩层大小不匹配：manifest=${parsed.archiveSizeBytes}, actual=$copied"
                }
                require(actualSha == parsed.archiveSha256) {
                    "rootfs SHA-256 不匹配：manifest=${parsed.archiveSha256}, actual=$actualSha"
                }
                parsed
            }

            layout.updateRootfsState(RuntimeRootfsState.INSTALLING, manifest.version)
            onProgress("正在安全解包 Debian rootfs")
            deleteTreeNoFollow(layout.rootfsStagingRoot)
            check(layout.rootfsStagingRoot.mkdirs()) { "无法创建 rootfs staging 目录" }
            val extraction = extractArchive(
                archiveFile = archiveFile,
                destination = layout.rootfsStagingRoot,
                compression = manifest.compression,
                onProgress = onProgress,
            )

            onProgress("正在验证 rootfs 必需文件")
            manifest.requiredPaths.forEach { requiredPath ->
                val relative = requiredPath.removePrefix("/")
                val requiredFile = RootfsPathPolicy.resolveEntry(layout.rootfsStagingRoot, relative)
                require(requiredFile.exists()) { "rootfs 缺少必需路径：$requiredPath" }
            }
            val bash = File(layout.rootfsStagingRoot, "bin/bash")
            require(bash.isFile) { "rootfs 缺少 /bin/bash" }
            Os.chmod(bash.path, EXECUTABLE_MODE)

            File(layout.rootfsStagingRoot, "etc/autocrack-rootfs.json").apply {
                parentFile?.mkdirs()
                writeText(manifest.toJson().toString(2), Charsets.UTF_8)
            }

            onProgress("正在原子切换 rootfs")
            activateStaging(manifest)
            layout.updateRootfsState(RuntimeRootfsState.INSTALLED, manifest.version)
            val result = RootfsInstallResult(
                manifest = manifest,
                packagePath = packageFile.path,
                installedPath = layout.rootfsRoot.path,
                archiveBytes = archiveFile.length(),
                extractedEntries = extraction.first,
                extractedBytes = extraction.second,
                durationMillis = System.currentTimeMillis() - startedAt,
            )
            onProgress("Debian rootfs 安装完成")
            result
        } catch (exception: Exception) {
            layout.updateRootfsState(RuntimeRootfsState.BROKEN)
            deleteTreeNoFollow(layout.rootfsStagingRoot)
            throw exception
        } finally {
            archiveFile.delete()
        }
    }

    suspend fun uninstall(onProgress: (String) -> Unit = {}) = withContext(Dispatchers.IO) {
        onProgress("正在删除已安装 rootfs")
        deleteTreeNoFollow(layout.rootfsRoot)
        deleteTreeNoFollow(layout.rootfsStagingRoot)
        deleteTreeNoFollow(layout.rootfsBackupRoot)
        layout.installedRootfsManifestFile.delete()
        layout.updateRootfsState(RuntimeRootfsState.NOT_INSTALLED)
        onProgress("rootfs 已卸载")
    }

    private fun copyUriToFile(uri: Uri, destination: File) {
        destination.parentFile?.mkdirs()
        appContext.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法打开所选 rootfs 包" }
            FileOutputStream(destination).buffered().use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_PACKAGE_BYTES) { "rootfs 包超过允许上限" }
                    output.write(buffer, 0, count)
                }
            }
        }
    }

    private fun extractArchive(
        archiveFile: File,
        destination: File,
        compression: String,
        onProgress: (String) -> Unit,
    ): Pair<Int, Long> {
        val hardLinks = mutableListOf<PendingHardLink>()
        var entryCount = 0
        var extractedBytes = 0L
        val compressedInput = when (compression) {
            "xz" -> XZCompressorInputStream.builder()
                .setPath(archiveFile.toPath())
                .setDecompressConcatenated(true)
                .setMemoryLimitKiB(XZ_MEMORY_LIMIT_KIB)
                .get()

            "gzip" -> GzipCompressorInputStream.builder()
                .setPath(archiveFile.toPath())
                .setDecompressConcatenated(true)
                .get()

            else -> error("不支持的压缩格式：$compression")
        }

        TarArchiveInputStream(compressedInput).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                entryCount += 1
                require(entryCount <= MAX_ENTRIES) { "rootfs 条目数量超过上限" }
                val target = RootfsPathPolicy.resolveEntry(destination, entry.name)
                target.parentFile?.mkdirs()

                when {
                    entry.isDirectory -> {
                        if (!target.exists()) check(target.mkdirs()) { "无法创建目录：${entry.name}" }
                        applyMode(target, entry.mode)
                    }

                    entry.isSymbolicLink -> {
                        if (target.exists() || Files.isSymbolicLink(target.toPath())) target.delete()
                        Os.symlink(entry.linkName, target.path)
                    }

                    entry.isLink -> {
                        hardLinks += PendingHardLink(
                            target = target,
                            linkName = entry.linkName,
                            mode = entry.mode,
                        )
                    }

                    entry.isFile -> {
                        require(entry.size >= 0L) { "rootfs 文件大小非法：${entry.name}" }
                        extractedBytes += entry.size
                        require(extractedBytes <= MAX_EXTRACTED_BYTES) {
                            "rootfs 解包大小超过上限"
                        }
                        FileOutputStream(target).buffered().use { output ->
                            val buffer = ByteArray(COPY_BUFFER_BYTES)
                            var remaining = entry.size
                            while (remaining > 0L) {
                                val count = tar.read(
                                    buffer,
                                    0,
                                    minOf(buffer.size.toLong(), remaining).toInt(),
                                )
                                require(count > 0) { "rootfs 文件提前结束：${entry.name}" }
                                output.write(buffer, 0, count)
                                remaining -= count
                            }
                        }
                        applyMode(target, entry.mode)
                    }

                    else -> error("暂不支持的 rootfs 特殊条目：${entry.name}")
                }

                if (entryCount % PROGRESS_ENTRY_INTERVAL == 0) {
                    onProgress("已解包 $entryCount 个条目，${extractedBytes / 1_048_576L} MiB")
                }
            }
        }

        var materializedHardLinks = 0
        hardLinks.forEach { hardLink ->
            val restored = restoreHardLink(
                destination = destination,
                hardLink = hardLink,
                remainingBytes = MAX_EXTRACTED_BYTES - extractedBytes,
            )
            extractedBytes += restored.copiedBytes
            if (restored.materialized) materializedHardLinks += 1
        }
        if (materializedHardLinks > 0) {
            onProgress("系统限制硬链接，已将 $materializedHardLinks 个条目安全复制")
        }
        return entryCount to extractedBytes
    }

    private fun restoreHardLink(
        destination: File,
        hardLink: PendingHardLink,
        remainingBytes: Long,
    ): HardLinkRestoreResult {
        val source = RootfsPathPolicy.resolveEntry(destination, hardLink.linkName)
        require(source.isFile && !Files.isSymbolicLink(source.toPath())) {
            "rootfs 硬链接目标不是普通文件：${hardLink.linkName}"
        }
        Files.deleteIfExists(hardLink.target.toPath())

        return try {
            Os.link(source.path, hardLink.target.path)
            applyMode(hardLink.target, hardLink.mode)
            HardLinkRestoreResult(copiedBytes = 0L, materialized = false)
        } catch (exception: ErrnoException) {
            if (!RootfsHardLinkPolicy.shouldMaterialize(exception.errno)) throw exception

            val expectedBytes = source.length()
            require(expectedBytes <= remainingBytes) {
                "rootfs 硬链接复制会超过解包大小上限：${hardLink.linkName}"
            }
            val copiedBytes = copyRegularFile(
                source = source,
                target = hardLink.target,
                maxBytes = remainingBytes,
            )
            require(copiedBytes == expectedBytes) {
                "rootfs 硬链接复制大小不匹配：${hardLink.linkName}"
            }
            applyMode(hardLink.target, hardLink.mode)
            HardLinkRestoreResult(copiedBytes = copiedBytes, materialized = true)
        }
    }

    private fun copyRegularFile(
        source: File,
        target: File,
        maxBytes: Long,
    ): Long {
        var copied = 0L
        source.inputStream().buffered().use { input ->
            FileOutputStream(target).buffered().use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    copied += count
                    require(copied <= maxBytes) { "rootfs 硬链接复制超过允许上限" }
                    output.write(buffer, 0, count)
                }
            }
        }
        return copied
    }

    private fun activateStaging(manifest: RootfsPackageManifest) {
        deleteTreeNoFollow(layout.rootfsBackupRoot)
        if (layout.rootfsRoot.exists()) {
            Files.move(
                layout.rootfsRoot.toPath(),
                layout.rootfsBackupRoot.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        try {
            Files.move(
                layout.rootfsStagingRoot.toPath(),
                layout.rootfsRoot.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
            layout.installedRootfsManifestFile.writeText(
                manifest.toJson().toString(2),
                Charsets.UTF_8,
            )
            deleteTreeNoFollow(layout.rootfsBackupRoot)
        } catch (exception: Exception) {
            deleteTreeNoFollow(layout.rootfsRoot)
            if (layout.rootfsBackupRoot.exists()) {
                Files.move(
                    layout.rootfsBackupRoot.toPath(),
                    layout.rootfsRoot.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            throw exception
        }
    }

    private fun applyMode(file: File, mode: Int) {
        if (mode > 0) Os.chmod(file.path, mode and PERMISSION_MASK)
    }

    private fun deleteTreeNoFollow(file: File) {
        val path = file.toPath()
        if (!Files.exists(path) && !Files.isSymbolicLink(path)) return
        Files.walkFileTree(
            path,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(
                    visitedFile: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    Files.deleteIfExists(visitedFile)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(
                    directory: Path,
                    error: java.io.IOException?,
                ): FileVisitResult {
                    if (error != null) throw error
                    Files.deleteIfExists(directory)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val MANIFEST_ENTRY = "manifest.json"
        const val MAX_PACKAGE_BYTES = 1_600_000_000L
        const val MAX_EXTRACTED_BYTES = 6_000_000_000L
        const val MAX_ENTRIES = 500_000
        const val COPY_BUFFER_BYTES = 128 * 1024
        const val PROGRESS_ENTRY_INTERVAL = 2_000
        const val XZ_MEMORY_LIMIT_KIB = 256 * 1024
        const val PERMISSION_MASK = 0xFFF
        const val EXECUTABLE_MODE = 0x1ED
    }
}
