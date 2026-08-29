package com.luckylca.autocrack.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.luckylca.autocrack.runtime.RuntimeLayout
import java.io.File

internal object MobileAgentFileActions {
    fun resolve(
        layout: RuntimeLayout,
        conversationId: String,
        relativePath: String,
    ): File {
        require(relativePath.isNotBlank() && !relativePath.startsWith('/')) { "文件路径必须位于当前会话 workspace" }
        require(!relativePath.split('/').any { it == ".." }) { "文件路径不能包含 .." }
        val workspace = layout.createAgentWorkspace(conversationId).canonicalFile
        val file = File(workspace, relativePath).canonicalFile
        require(file.path == workspace.path || file.path.startsWith("${workspace.path}${File.separator}")) { "文件路径越界" }
        require(file.isFile) { "文件不存在：$relativePath" }
        return file
    }

    fun open(context: Context, file: File, explicitMimeType: String? = null) {
        val uri = contentUri(context, file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, explicitMimeType ?: guessMimeType(file.name))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "打开 ${file.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun share(context: Context, file: File, explicitMimeType: String? = null) {
        val uri = contentUri(context, file)
        val intent = Intent(Intent.ACTION_SEND)
            .setType(explicitMimeType ?: guessMimeType(file.name))
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "分享 ${file.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun copyToUri(context: Context, file: File, destination: Uri) {
        context.contentResolver.openOutputStream(destination, "wt").use { output ->
            requireNotNull(output) { "无法写入所选位置" }
            file.inputStream().buffered().use { input -> input.copyTo(output) }
        }
    }

    fun guessMimeType(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: when (extension) {
            "apk" -> "application/vnd.android.package-archive"
            "json" -> "application/json"
            "txt", "log", "md", "py", "js", "kt", "java", "c", "cpp", "h" -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    private fun contentUri(context: Context, file: File): Uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.agentfiles",
        file,
    )
}
