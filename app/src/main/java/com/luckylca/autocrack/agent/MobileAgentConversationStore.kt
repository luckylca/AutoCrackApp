package com.luckylca.autocrack.agent

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.luckylca.autocrack.runtime.RuntimeLayout
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
    val compactionCount: Int = 0,
)

/** Transactional conversation storage with a one-time import from the legacy monolithic JSON file. */
class MobileAgentConversationStore(context: Context) {
    private val layout = RuntimeLayout(context.applicationContext).initialize()
    private val databaseFile = File(layout.sessionsRoot, DATABASE_NAME)
    private val legacyFile = File(layout.sessionsRoot, LEGACY_FILE_NAME)
    private val lock = locks.computeIfAbsent(databaseFile.canonicalPath) { Any() }

    suspend fun list(): List<MobileAgentConversation> = withDatabase { database ->
        readConversations(database, includeMessages = true)
            .sortedByDescending(MobileAgentConversation::updatedAtEpochMillis)
    }

    suspend fun listMetadata(): List<MobileAgentConversation> = withDatabase { database ->
        readConversations(database, includeMessages = false)
            .sortedByDescending(MobileAgentConversation::updatedAtEpochMillis)
    }

    suspend fun get(conversationId: String): MobileAgentConversation? = withDatabase { database ->
        readConversation(database, conversationId, includeMessages = true)
    }

    suspend fun searchIds(query: String): Set<String> = withDatabase { database ->
        val normalized = query.trim()
        if (normalized.isBlank()) return@withDatabase emptySet()
        val pattern = "%${escapeLikePattern(normalized)}%"
        database.rawQuery(
            """
                SELECT DISTINCT c.id
                FROM conversations c
                LEFT JOIN messages m ON m.conversation_id = c.id
                WHERE c.title LIKE ? ESCAPE '\' COLLATE NOCASE
                   OR m.content LIKE ? ESCAPE '\' COLLATE NOCASE
            """.trimIndent(),
            arrayOf(pattern, pattern),
        ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
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
            val workspace = layout.createAgentWorkspace(conversation.id)
            MobileAgentWorkspacePolicy.markIsolated(workspace)
            try {
                openDatabase().use { database -> insertConversation(database, conversation) }
            } catch (error: Exception) {
                deleteTree(workspace)
                throw error
            }
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
    ): MobileAgentConversation = withDatabase { database ->
        require(messages.isNotEmpty()) { "消息列表不能为空" }
        require(messages.all { it.content.isNotBlank() || it.toolCallsJson != null || it.attachments.isNotEmpty() }) {
            "消息内容不能为空"
        }
        database.beginTransaction()
        try {
            val current = requireNotNull(readConversation(database, conversationId, includeMessages = false)) {
                "会话不存在：$conversationId"
            }
            val hasExistingUser = database.rawQuery(
                "SELECT 1 FROM messages WHERE conversation_id = ? AND role = ? LIMIT 1",
                arrayOf(conversationId, MobileAgentRole.USER.name),
            ).use(Cursor::moveToFirst)
            var sequence = nextSequence(database, conversationId)
            messages.forEach { message ->
                insertMessage(database, conversationId, sequence, message)
                sequence += 1
            }
            val firstUser = messages.firstOrNull { it.role == MobileAgentRole.USER }?.takeIf { !hasExistingUser }
            val title = firstUser?.content
                ?.trim()
                ?.replace('\n', ' ')
                ?.take(36)
                ?.ifBlank { "新会话" }
                ?: current.title
            val now = System.currentTimeMillis()
            val updatedRows = database.update(
                "conversations",
                ContentValues().apply {
                    put("title", title)
                    put("updated_at", now)
                },
                "id = ?",
                arrayOf(conversationId),
            )
            check(updatedRows == 1) { "无法更新会话：$conversationId" }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        requireNotNull(readConversation(database, conversationId, includeMessages = true))
    }

    suspend fun updateSummary(
        conversationId: String,
        summary: String,
        throughMessageId: String,
    ): MobileAgentConversation = withDatabase { database ->
        require(summary.isNotBlank()) { "会话摘要不能为空" }
        val boundaryExists = database.rawQuery(
            "SELECT 1 FROM messages WHERE conversation_id = ? AND id = ? LIMIT 1",
            arrayOf(conversationId, throughMessageId),
        ).use(Cursor::moveToFirst)
        require(boundaryExists) { "摘要边界消息不存在" }
        val updatedRows = database.update(
            "conversations",
            ContentValues().apply {
                put("updated_at", System.currentTimeMillis())
                put("summary", summary.trim())
                put("summary_through_message_id", throughMessageId)
                put("compaction_count", currentCompactionCount(database, conversationId) + 1)
            },
            "id = ?",
            arrayOf(conversationId),
        )
        require(updatedRows == 1) { "会话不存在：$conversationId" }
        requireNotNull(readConversation(database, conversationId, includeMessages = true))
    }

    suspend fun rename(conversationId: String, title: String): MobileAgentConversation = withDatabase { database ->
        val normalized = title.trim().replace('\n', ' ').take(80)
        require(normalized.isNotBlank()) { "会话标题不能为空" }
        val updatedRows = database.update(
            "conversations",
            ContentValues().apply {
                put("title", normalized)
                put("updated_at", System.currentTimeMillis())
            },
            "id = ?",
            arrayOf(conversationId),
        )
        require(updatedRows == 1) { "会话不存在：$conversationId" }
        requireNotNull(readConversation(database, conversationId, includeMessages = true))
    }

    suspend fun delete(conversationId: String) = withDatabase { database ->
        database.delete("conversations", "id = ?", arrayOf(conversationId))
        deleteTree(layout.createAgentWorkspace(conversationId))
    }

    private suspend fun <T> withDatabase(block: (SQLiteDatabase) -> T): T = withContext(Dispatchers.IO) {
        synchronized(lock) { openDatabase().use(block) }
    }

    private fun openDatabase(): SQLiteDatabase {
        databaseFile.parentFile?.mkdirs()
        val database = SQLiteDatabase.openOrCreateDatabase(databaseFile, null)
        try {
            database.execSQL("PRAGMA foreign_keys = ON")
            createSchema(database)
            migrateLegacyJsonIfNeeded(database)
            return database
        } catch (error: Exception) {
            database.close()
            throw error
        }
    }

    private fun createSchema(database: SQLiteDatabase) {
        database.execSQL(
            """
                CREATE TABLE IF NOT EXISTS conversations (
                    id TEXT PRIMARY KEY NOT NULL,
                    title TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    summary TEXT,
                    summary_through_message_id TEXT,
                    compaction_count INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent(),
        )
        database.execSQL(
            """
                CREATE TABLE IF NOT EXISTS messages (
                    id TEXT PRIMARY KEY NOT NULL,
                    conversation_id TEXT NOT NULL,
                    sequence_number INTEGER NOT NULL,
                    role TEXT NOT NULL,
                    content TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    tool_call_id TEXT,
                    tool_name TEXT,
                    tool_calls_json TEXT,
                    attachments_json TEXT NOT NULL,
                    FOREIGN KEY(conversation_id) REFERENCES conversations(id) ON DELETE CASCADE,
                    UNIQUE(conversation_id, sequence_number)
                )
            """.trimIndent(),
        )
        database.execSQL("CREATE INDEX IF NOT EXISTS idx_conversations_updated ON conversations(updated_at DESC)")
        database.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_conversation ON messages(conversation_id, sequence_number)")
        database.execSQL("PRAGMA user_version = $DATABASE_SCHEMA_VERSION")
    }

    private fun migrateLegacyJsonIfNeeded(database: SQLiteDatabase) {
        if (!legacyFile.isFile || conversationCount(database) > 0L) return
        val legacyConversations = parseLegacyFile(legacyFile)
        database.beginTransaction()
        try {
            legacyConversations.forEach { conversation ->
                insertConversation(database, conversation)
                conversation.messages.forEachIndexed { sequence, message ->
                    insertMessage(database, conversation.id, sequence, message)
                }
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        val backup = uniqueLegacyBackupFile()
        if (!legacyFile.renameTo(backup)) {
            throw IOException("会话已迁移，但无法保留旧 JSON 备份：${backup.path}")
        }
    }

    private fun parseLegacyFile(source: File): List<MobileAgentConversation> {
        val root = try {
            JSONObject(source.readText(Charsets.UTF_8))
        } catch (error: Exception) {
            throw IOException("旧会话文件损坏，已拒绝覆盖：${source.path}", error)
        }
        val array = root.optJSONArray("conversations")
            ?: throw IOException("旧会话文件缺少 conversations 数组：${source.path}")
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(parseLegacyConversation(item))
            }
        }
    }

    private fun parseLegacyConversation(json: JSONObject): MobileAgentConversation {
        val messages = buildList {
            val messageArray = json.optJSONArray("messages") ?: JSONArray()
            for (index in 0 until messageArray.length()) {
                val item = messageArray.optJSONObject(index) ?: continue
                val role = runCatching { MobileAgentRole.valueOf(item.getString("role")) }.getOrNull() ?: continue
                val content = item.optString("content")
                val toolCallsJson = item.optJSONArray("toolCalls")?.toString()
                    ?: item.optNonBlankStringOrNull("toolCallsJson")
                val attachments = item.optJSONArray("attachments")?.toAttachmentList().orEmpty()
                if (content.isBlank() && toolCallsJson == null && attachments.isEmpty()) continue
                add(
                    MobileAgentMessage(
                        id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                        role = role,
                        content = content,
                        createdAtEpochMillis = item.optLong("createdAtEpochMillis"),
                        toolCallId = item.optNonBlankStringOrNull("toolCallId"),
                        toolName = item.optNonBlankStringOrNull("toolName"),
                        toolCallsJson = toolCallsJson,
                        attachments = attachments,
                    ),
                )
            }
        }
        return MobileAgentConversation(
            id = json.getString("id"),
            title = json.optString("title", "新会话"),
            createdAtEpochMillis = json.optLong("createdAtEpochMillis"),
            updatedAtEpochMillis = json.optLong("updatedAtEpochMillis"),
            messages = messages,
            summary = json.optNonBlankStringOrNull("summary"),
            summaryThroughMessageId = json.optNonBlankStringOrNull("summaryThroughMessageId"),
            compactionCount = json.optInt("compactionCount", 0).coerceAtLeast(0),
        )
    }

    private fun insertConversation(database: SQLiteDatabase, conversation: MobileAgentConversation) {
        database.insertOrThrow(
            "conversations",
            null,
            ContentValues().apply {
                put("id", conversation.id)
                put("title", conversation.title)
                put("created_at", conversation.createdAtEpochMillis)
                put("updated_at", conversation.updatedAtEpochMillis)
                putNullable("summary", conversation.summary)
                putNullable("summary_through_message_id", conversation.summaryThroughMessageId)
                put("compaction_count", conversation.compactionCount)
            },
        )
    }

    private fun insertMessage(
        database: SQLiteDatabase,
        conversationId: String,
        sequence: Int,
        message: MobileAgentMessage,
    ) {
        database.insertOrThrow(
            "messages",
            null,
            ContentValues().apply {
                put("id", message.id)
                put("conversation_id", conversationId)
                put("sequence_number", sequence)
                put("role", message.role.name)
                put("content", message.content)
                put("created_at", message.createdAtEpochMillis)
                putNullable("tool_call_id", message.toolCallId)
                putNullable("tool_name", message.toolName)
                putNullable("tool_calls_json", message.toolCallsJson)
                put("attachments_json", JSONArray(message.attachments.map { attachment -> attachment.toJson() }).toString())
            },
        )
    }

    private fun readConversations(database: SQLiteDatabase, includeMessages: Boolean): List<MobileAgentConversation> =
        database.rawQuery("SELECT id FROM conversations ORDER BY updated_at DESC", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    readConversation(database, cursor.getString(0), includeMessages)?.let(::add)
                }
            }
        }

    private fun readConversation(
        database: SQLiteDatabase,
        conversationId: String,
        includeMessages: Boolean,
    ): MobileAgentConversation? {
        val conversation = database.rawQuery(
            """
                SELECT id, title, created_at, updated_at, summary,
                       summary_through_message_id, compaction_count
                FROM conversations WHERE id = ?
            """.trimIndent(),
            arrayOf(conversationId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            MobileAgentConversation(
                id = cursor.getString(0),
                title = cursor.getString(1),
                createdAtEpochMillis = cursor.getLong(2),
                updatedAtEpochMillis = cursor.getLong(3),
                messages = emptyList(),
                summary = cursor.getNullableString(4),
                summaryThroughMessageId = cursor.getNullableString(5),
                compactionCount = cursor.getInt(6).coerceAtLeast(0),
            )
        }
        if (!includeMessages) return conversation
        return conversation.copy(messages = readMessages(database, conversationId))
    }

    private fun readMessages(database: SQLiteDatabase, conversationId: String): List<MobileAgentMessage> =
        database.rawQuery(
            """
                SELECT id, role, content, created_at, tool_call_id, tool_name,
                       tool_calls_json, attachments_json
                FROM messages WHERE conversation_id = ? ORDER BY sequence_number
            """.trimIndent(),
            arrayOf(conversationId),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val role = runCatching { MobileAgentRole.valueOf(cursor.getString(1)) }.getOrNull() ?: continue
                    add(
                        MobileAgentMessage(
                            id = cursor.getString(0),
                            role = role,
                            content = cursor.getString(2),
                            createdAtEpochMillis = cursor.getLong(3),
                            toolCallId = cursor.getNullableString(4),
                            toolName = cursor.getNullableString(5),
                            toolCallsJson = cursor.getNullableString(6),
                            attachments = runCatching { JSONArray(cursor.getString(7)).toAttachmentList() }.getOrDefault(emptyList()),
                        ),
                    )
                }
            }
        }

    private fun nextSequence(database: SQLiteDatabase, conversationId: String): Int = database.rawQuery(
        "SELECT COALESCE(MAX(sequence_number), -1) + 1 FROM messages WHERE conversation_id = ?",
        arrayOf(conversationId),
    ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }

    private fun currentCompactionCount(database: SQLiteDatabase, conversationId: String): Int = database.rawQuery(
        "SELECT compaction_count FROM conversations WHERE id = ?",
        arrayOf(conversationId),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else error("会话不存在：$conversationId") }

    private fun conversationCount(database: SQLiteDatabase): Long = database.rawQuery(
        "SELECT COUNT(*) FROM conversations",
        null,
    ).use { cursor -> cursor.moveToFirst(); cursor.getLong(0) }

    private fun uniqueLegacyBackupFile(): File {
        val preferred = File(legacyFile.parentFile, "$LEGACY_FILE_NAME.migrated-v$DATABASE_SCHEMA_VERSION")
        if (!preferred.exists()) return preferred
        return File(legacyFile.parentFile, "$LEGACY_FILE_NAME.migrated-v$DATABASE_SCHEMA_VERSION-${System.currentTimeMillis()}")
    }

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
                    mimeType = item.optNonBlankStringOrNull("mimeType"),
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

    private companion object {
        const val DATABASE_NAME = "mobile-agent.db"
        const val DATABASE_SCHEMA_VERSION = 1
        const val LEGACY_FILE_NAME = "mobile-agent-conversations.json"
        val locks = ConcurrentHashMap<String, Any>()
    }
}

private fun Cursor.getNullableString(index: Int): String? = if (isNull(index)) null else getString(index)

private fun ContentValues.putNullable(key: String, value: String?) {
    if (value == null) putNull(key) else put(key, value)
}

private fun escapeLikePattern(value: String): String = value
    .replace("\\", "\\\\")
    .replace("%", "\\%")
    .replace("_", "\\_")
