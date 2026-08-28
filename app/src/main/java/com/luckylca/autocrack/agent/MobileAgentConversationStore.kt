package com.luckylca.autocrack.agent

import android.content.Context
import com.luckylca.autocrack.runtime.RuntimeLayout
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class MobileAgentRole { USER, ASSISTANT, TOOL }

data class MobileAgentAttachment(
    val id: String,
    val displayName: String,
    val relativePath: String,
    val mimeType: String?,
    val sizeBytes: Long,
) {
    val agentPath: String
        get() = "/workspace/$relativePath"
}

data class MobileAgentMessage(
    val id: String,
    val role: MobileAgentRole,
    val content: String,
    val createdAtEpochMillis: Long,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolCallsJson: String? = null,
    val attachments: List<MobileAgentAttachment> = emptyList(),
) {
    val visibleInConversation: Boolean
        get() = role != MobileAgentRole.TOOL && (content.isNotBlank() || attachments.isNotEmpty())
}

data class MobileAgentConversation(
    val id: String,
    val title: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val messages: List<MobileAgentMessage>,
    val summary: String? = null,
    val summaryThroughMessageId: String? = null,
)

class MobileAgentConversationStore(context: Context) {
    private val layout = RuntimeLayout(context.applicationContext).initialize()
    private val file = File(layout.sessionsRoot, "mobile-agent-conversations.json")
    private val lock = Any()

    suspend fun list(): List<MobileAgentConversation> = withContext(Dispatchers.IO) {
        synchronized(lock) { readAll().sortedByDescending(MobileAgentConversation::updatedAtEpochMillis) }
    }

    suspend fun get(conversationId: String): MobileAgentConversation? = withContext(Dispatchers.IO) {
        synchronized(lock) { readAll().firstOrNull { it.id == conversationId } }
    }

    suspend fun create(): MobileAgentConversation = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val conversation = MobileAgentConversation(
                id = UUID.randomUUID().toString(),
                title = "新会话",
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
                messages = emptyList(),
            )
            writeAll(readAll() + conversation)
            conversation
        }
    }

    suspend fun append(
        conversationId: String,
        role: MobileAgentRole,
        content: String,
    ): MobileAgentConversation = when (role) {
        MobileAgentRole.USER -> appendUser(conversationId, content)
        MobileAgentRole.ASSISTANT,
        MobileAgentRole.TOOL,
        -> appendGenerated(
            conversationId,
            MobileAgentMessage(
                id = UUID.randomUUID().toString(),
                role = role,
                content = content.trim(),
                createdAtEpochMillis = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun appendUser(
        conversationId: String,
        content: String,
        attachments: List<MobileAgentAttachment> = emptyList(),
    ): MobileAgentConversation = appendMessages(
        conversationId,
        listOf(
            MobileAgentMessage(
                id = UUID.randomUUID().toString(),
                role = MobileAgentRole.USER,
                content = content.trim(),
                createdAtEpochMillis = System.currentTimeMillis(),
                attachments = attachments,
            ),
        ),
    )

    suspend fun appendGenerated(
        conversationId: String,
        message: MobileAgentMessage,
    ): MobileAgentConversation = appendMessages(conversationId, listOf(message))

    suspend fun appendMessages(
        conversationId: String,
        messages: List<MobileAgentMessage>,
    ): MobileAgentConversation = withContext(Dispatchers.IO) {
        require(messages.isNotEmpty()) { "消息列表不能为空" }
        require(messages.all { it.content.isNotBlank() || it.toolCallsJson != null || it.attachments.isNotEmpty() }) {
            "消息内容不能为空"
        }
        synchronized(lock) {
            val all = readAll().toMutableList()
            val index = all.indexOfFirst { it.id == conversationId }
            require(index >= 0) { "会话不存在：$conversationId" }
            val current = all[index]
            val firstUser = messages.firstOrNull { it.role == MobileAgentRole.USER }
                ?.takeIf { current.messages.none { old -> old.role == MobileAgentRole.USER } }
            val title = firstUser?.content
                ?.trim()
                ?.replace('\n', ' ')
                ?.take(36)
                ?.ifBlank { "新会话" }
                ?: current.title
            val updated = current.copy(
                title = title,
                updatedAtEpochMillis = System.currentTimeMillis(),
                messages = current.messages + messages,
            )
            all[index] = updated
            writeAll(all)
            updated
        }
    }

    suspend fun updateSummary(
        conversationId: String,
        summary: String,
        throughMessageId: String,
    ): MobileAgentConversation = withContext(Dispatchers.IO) {
        require(summary.isNotBlank()) { "会话摘要不能为空" }
        synchronized(lock) {
            val all = readAll().toMutableList()
            val index = all.indexOfFirst { it.id == conversationId }
            require(index >= 0) { "会话不存在：$conversationId" }
            val current = all[index]
            require(current.messages.any { it.id == throughMessageId }) { "摘要边界消息不存在" }
            val updated = current.copy(
                updatedAtEpochMillis = System.currentTimeMillis(),
                summary = summary.trim(),
                summaryThroughMessageId = throughMessageId,
            )
            all[index] = updated
            writeAll(all)
            updated
        }
    }

    suspend fun rename(conversationId: String, title: String): MobileAgentConversation = withContext(Dispatchers.IO) {
        val normalized = title.trim().replace('\n', ' ').take(80)
        require(normalized.isNotBlank()) { "会话标题不能为空" }
        synchronized(lock) {
            val all = readAll().toMutableList()
            val index = all.indexOfFirst { it.id == conversationId }
            require(index >= 0) { "会话不存在：$conversationId" }
            val updated = all[index].copy(title = normalized, updatedAtEpochMillis = System.currentTimeMillis())
            all[index] = updated
            writeAll(all)
            updated
        }
    }

    suspend fun delete(conversationId: String) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            writeAll(readAll().filterNot { it.id == conversationId })
            deleteTree(layout.createAgentWorkspace(conversationId))
        }
    }

    private fun readAll(): List<MobileAgentConversation> {
        if (!file.isFile) return emptyList()
        return runCatching {
            val array = JSONObject(file.readText()).optJSONArray("conversations") ?: JSONArray()
            buildList {
                for (index in 0 until array.length()) {
                    parse(array.optJSONObject(index) ?: continue)?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun parse(json: JSONObject): MobileAgentConversation? = runCatching {
        val messageArray = json.optJSONArray("messages") ?: JSONArray()
        val messages = buildList {
            for (index in 0 until messageArray.length()) {
                val item = messageArray.optJSONObject(index) ?: continue
                val role = runCatching { MobileAgentRole.valueOf(item.getString("role")) }.getOrNull() ?: continue
                val content = item.optString("content")
                val toolCallsJson = item.optJSONArray("toolCalls")?.toString()
                    ?: item.optString("toolCallsJson").takeIf(String::isNotBlank)
                val attachments = item.optJSONArray("attachments")?.toAttachmentList().orEmpty()
                if (content.isBlank() && toolCallsJson == null && attachments.isEmpty()) continue
                add(
                    MobileAgentMessage(
                        id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                        role = role,
                        content = content,
                        createdAtEpochMillis = item.optLong("createdAtEpochMillis"),
                        toolCallId = item.optString("toolCallId").takeIf(String::isNotBlank),
                        toolName = item.optString("toolName").takeIf(String::isNotBlank),
                        toolCallsJson = toolCallsJson,
                        attachments = attachments,
                    ),
                )
            }
        }
        MobileAgentConversation(
            id = json.getString("id"),
            title = json.optString("title", "新会话"),
            createdAtEpochMillis = json.optLong("createdAtEpochMillis"),
            updatedAtEpochMillis = json.optLong("updatedAtEpochMillis"),
            messages = messages,
            summary = json.optString("summary").takeIf(String::isNotBlank),
            summaryThroughMessageId = json.optString("summaryThroughMessageId").takeIf(String::isNotBlank),
        )
    }.getOrNull()

    private fun writeAll(all: List<MobileAgentConversation>) {
        val root = JSONObject()
            .put("schemaVersion", 2)
            .put(
                "conversations",
                JSONArray().apply {
                    all.forEach { conversation ->
                        put(
                            JSONObject()
                                .put("id", conversation.id)
                                .put("title", conversation.title)
                                .put("createdAtEpochMillis", conversation.createdAtEpochMillis)
                                .put("updatedAtEpochMillis", conversation.updatedAtEpochMillis)
                                .put("summary", conversation.summary ?: JSONObject.NULL)
                                .put("summaryThroughMessageId", conversation.summaryThroughMessageId ?: JSONObject.NULL)
                                .put(
                                    "messages",
                                    JSONArray().apply {
                                        conversation.messages.forEach { message -> put(message.toJson()) }
                                    },
                                ),
                        )
                    }
                },
            )
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(root.toString(2))
        if (!temp.renameTo(file)) {
            file.writeText(root.toString(2))
            temp.delete()
        }
    }

    private fun MobileAgentMessage.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("role", role.name)
        .put("content", content)
        .put("createdAtEpochMillis", createdAtEpochMillis)
        .put("toolCallId", toolCallId ?: JSONObject.NULL)
        .put("toolName", toolName ?: JSONObject.NULL)
        .put("toolCalls", toolCallsJson?.let(::JSONArray) ?: JSONObject.NULL)
        .put("attachments", JSONArray(attachments.map { attachment -> attachment.toJson() }))

    private fun MobileAgentAttachment.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("displayName", displayName)
        .put("relativePath", relativePath)
        .put("mimeType", mimeType ?: JSONObject.NULL)
        .put("sizeBytes", sizeBytes)

    private fun JSONArray.toAttachmentList(): List<MobileAgentAttachment> = buildList {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            val relativePath = item.optString("relativePath")
            if (relativePath.isBlank()) continue
            add(
                MobileAgentAttachment(
                    id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                    displayName = item.optString("displayName").ifBlank { File(relativePath).name },
                    relativePath = relativePath,
                    mimeType = item.optString("mimeType").takeIf(String::isNotBlank),
                    sizeBytes = item.optLong("sizeBytes"),
                ),
            )
        }
    }

    private fun deleteTree(file: File) {
        if (!file.exists()) return
        if (file.isDirectory && !java.nio.file.Files.isSymbolicLink(file.toPath())) {
            file.listFiles().orEmpty().forEach(::deleteTree)
        }
        file.delete()
    }
}
