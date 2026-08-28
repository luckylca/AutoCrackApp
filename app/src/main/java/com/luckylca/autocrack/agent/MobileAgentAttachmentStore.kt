package com.luckylca.autocrack.agent

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.luckylca.autocrack.runtime.RuntimeLayout
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MobileAgentAttachmentStore(context: Context) {
    private val appContext = context.applicationContext
    private val layout = RuntimeLayout(appContext).initialize()

    suspend fun import(
        conversationId: String,
        uris: List<Uri>,
    ): List<MobileAgentAttachment> = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext emptyList()
        val workspace = layout.createAgentWorkspace(conversationId)
        val attachmentsRoot = File(workspace, "attachments").canonicalFile
        require(attachmentsRoot.path.startsWith(workspace.canonicalPath + File.separator)) {
            "附件目录越界"
        }
        if (!attachmentsRoot.exists()) check(attachmentsRoot.mkdirs()) { "无法创建附件目录" }

        uris.map { uri -> importOne(uri, attachmentsRoot) }
    }

    private fun importOne(uri: Uri, attachmentsRoot: File): MobileAgentAttachment {
        val metadata = queryMetadata(uri)
        val safeName = sanitizeName(metadata.first ?: "attachment")
        val id = UUID.randomUUID().toString()
        val target = File(attachmentsRoot, "$id-$safeName").canonicalFile
        require(target.path.startsWith(attachmentsRoot.path + File.separator)) { "附件路径越界" }
        var copied = 0L
        appContext.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法打开附件：$safeName" }
            FileOutputStream(target).buffered().use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    copied += count
                    require(copied <= MAX_ATTACHMENT_BYTES) { "附件超过允许上限：$safeName" }
                    output.write(buffer, 0, count)
                }
            }
        }
        require(copied > 0) { "附件为空：$safeName" }
        val relativePath = "attachments/${target.name}"
        return MobileAgentAttachment(
            id = id,
            displayName = safeName,
            relativePath = relativePath,
            mimeType = appContext.contentResolver.getType(uri),
            sizeBytes = copied,
        )
    }

    private fun queryMetadata(uri: Uri): Pair<String?, Long?> {
        var name: String? = null
        var size: Long? = null
        appContext.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) name = cursor.getString(nameIndex)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }
        size?.let { require(it in 1..MAX_ATTACHMENT_BYTES) { "附件大小非法：$it B" } }
        return name to size
    }

    private fun sanitizeName(input: String): String {
        val normalized = input
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim()
            .take(MAX_FILENAME_CHARS)
        return normalized.ifBlank { "attachment" }
    }

    private companion object {
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val MAX_ATTACHMENT_BYTES = 1_500_000_000L
        const val MAX_FILENAME_CHARS = 120
    }
}
