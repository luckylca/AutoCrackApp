package com.luckylca.autocrack.agent

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Streaming tool loop supporting OpenAI Chat Completions and Anthropic Messages APIs. */
class MobileAgentToolClient {
    @Volatile
    private var activeConnection: HttpURLConnection? = null

    @Volatile
    private var cancelled = false

    fun cancelCurrent() {
        cancelled = true
        activeConnection?.disconnect()
    }

    suspend fun completeWithTools(
        config: LlmProviderConfig,
        systemPrompt: String,
        conversation: MobileAgentConversation,
        tools: List<AgentToolDefinition>,
        dispatcher: AgentToolDispatcher,
        onTextSnapshot: (String) -> Unit = {},
        onStage: (String) -> Unit = {},
        onProtocolMessage: suspend (MobileAgentMessage) -> Unit = {},
        maxToolRounds: Int = DEFAULT_MAX_TOOL_ROUNDS,
        contextCompressionEnabled: Boolean = true,
    ): MobileAgentCompletion = withContext(Dispatchers.IO) {
        require(tools.isNotEmpty()) { "Agent tool list must not be empty" }
        cancelled = false
        val validated = config.validated()
        val startedAt = System.currentTimeMillis()
        var messages = buildProtocolMessages(systemPrompt, conversation)
        val protocol = LlmEndpointNormalizer.protocol(validated.baseUrl)
        val toolJson = when (protocol) {
            LlmApiProtocol.OPENAI_CHAT -> JSONArray().also { array -> tools.forEach { array.put(it.toOpenAiJson()) } }
            LlmApiProtocol.ANTHROPIC_MESSAGES -> AnthropicMessagesAdapter.buildTools(tools)
        }
        val knownTools = tools.associateBy(AgentToolDefinition::name)
        val toolRequestCounts = mutableMapOf<String, Int>()
        var toolExecutionCount = 0
        require(maxToolRounds in 0..ABSOLUTE_MAX_TOOL_ROUNDS) {
            "maxToolRounds must be 0 (automatic) or within 1..$ABSOLUTE_MAX_TOOL_ROUNDS"
        }
        val effectiveRoundLimit = if (maxToolRounds == 0) ABSOLUTE_MAX_TOOL_ROUNDS else maxToolRounds

        repeat(effectiveRoundLimit) { roundIndex ->
            ensureRunning()
            if (contextCompressionEnabled) {
                messages = compactLoopContextIfNeeded(validated, messages, roundIndex + 1, onStage)
            }
            onStage("thinking:${roundIndex + 1}")
            onTextSnapshot("")
            val assistant = requestStreamingMessage(
                config = validated,
                messages = messages,
                tools = toolJson,
                onTextSnapshot = onTextSnapshot,
            )
            ensureRunning()
            val calls = assistant.optJSONArray("tool_calls")
            if (calls == null || calls.length() == 0) {
                val content = parseContent(assistant)
                if (content.isBlank()) throw IOException("模型结束 tool loop 时返回了空答案")
                val stored = MobileAgentMessage(
                    id = UUID.randomUUID().toString(),
                    role = MobileAgentRole.ASSISTANT,
                    content = content.take(MAX_ANSWER_CHARS),
                    createdAtEpochMillis = System.currentTimeMillis(),
                )
                onProtocolMessage(stored)
                return@withContext MobileAgentCompletion(
                    model = validated.model,
                    endpointHost = URI(validated.baseUrl).host.orEmpty(),
                    content = stored.content,
                    toolExecutionCount = toolExecutionCount,
                    startedAtEpochMillis = startedAt,
                    completedAtEpochMillis = System.currentTimeMillis(),
                )
            }

            require(calls.length() <= MAX_TOOL_CALLS_PER_ROUND) {
                "模型单轮请求了过多工具：${calls.length()} > $MAX_TOOL_CALLS_PER_ROUND"
            }
            val normalizedAssistant = normalizeAssistantToolMessage(assistant)
            messages.put(normalizedAssistant)
            onProtocolMessage(
                MobileAgentMessage(
                    id = UUID.randomUUID().toString(),
                    role = MobileAgentRole.ASSISTANT,
                    content = parseContent(assistant),
                    createdAtEpochMillis = System.currentTimeMillis(),
                    toolCallsJson = calls.toString(),
                ),
            )

            val roundResultCache = mutableMapOf<String, String>()
            for (index in 0 until calls.length()) {
                ensureRunning()
                val call = calls.getJSONObject(index)
                val callId = call.getString("id").take(MAX_CALL_ID_CHARS)
                val function = call.getJSONObject("function")
                val name = function.getString("name")
                require(name in knownTools) { "模型请求了未授权工具：$name" }
                val rawArguments = function.optString("arguments", "{}").ifBlank { "{}" }
                val arguments = try {
                    JSONObject(rawArguments)
                } catch (exception: Exception) {
                    JSONObject().put("_parse_error", exception.message ?: "invalid JSON")
                }
                val fingerprint = "$name\n${arguments.toString()}"
                val requestCount = toolRequestCounts.getOrDefault(fingerprint, 0) + 1
                toolRequestCounts[fingerprint] = requestCount
                if (requestCount > MAX_IDENTICAL_TOOL_REQUESTS) {
                    throw IOException(
                        "模型重复请求同一工具和参数超过 $MAX_IDENTICAL_TOOL_REQUESTS 次，已停止以避免循环：$name",
                    )
                }
                onStage("tool:$name")
                val cachedResult = roundResultCache[fingerprint]
                val result = cachedResult ?: runCatching { dispatcher(name, arguments) }
                    .getOrElse { error ->
                        JSONObject()
                            .put("ok", false)
                            .put("error", (error.message ?: error::class.java.simpleName).take(MAX_TOOL_RESULT_CHARS))
                            .toString()
                    }
                    .take(MAX_TOOL_RESULT_CHARS)
                    .also { roundResultCache[fingerprint] = it }
                ensureRunning()
                if (cachedResult == null) toolExecutionCount++
                val toolMessage = MobileAgentMessage(
                    id = UUID.randomUUID().toString(),
                    role = MobileAgentRole.TOOL,
                    content = result,
                    createdAtEpochMillis = System.currentTimeMillis(),
                    toolCallId = callId,
                    toolName = name,
                )
                messages.put(toolMessage.toOpenAiJson())
                onProtocolMessage(toolMessage)
            }
        }

        if (maxToolRounds == 0) {
            throw IOException("Agent tool loop 达到异常保护上限 $ABSOLUTE_MAX_TOOL_ROUNDS 轮，已停止；这通常表示模型陷入循环")
        }
        throw IOException("Agent tool loop 达到用户设置的 $maxToolRounds 轮上限，已停止")
    }

    private suspend fun compactLoopContextIfNeeded(
        config: LlmProviderConfig,
        messages: JSONArray,
        round: Int,
        onStage: (String) -> Unit,
    ): JSONArray {
        if (estimateProtocolChars(messages) < LOOP_COMPACTION_TRIGGER_CHARS) return messages
        val keepStart = recentContextStart(messages)
        if (keepStart <= 2) return messages

        onStage("compacting:$round")
        val compactableMessages = buildList {
            for (index in 1 until keepStart) {
                val message = messages.optJSONObject(index) ?: continue
                val role = when (message.optString("role")) {
                    "user" -> MobileAgentRole.USER
                    "tool" -> MobileAgentRole.TOOL
                    else -> MobileAgentRole.ASSISTANT
                }
                add(
                    MobileAgentMessage(
                        id = "loop-$index",
                        role = role,
                        content = message.optString("content"),
                        createdAtEpochMillis = 0,
                        toolName = message.optString("name").takeIf(String::isNotBlank),
                        toolCallsJson = message.optJSONArray("tool_calls")?.toString(),
                    ),
                )
            }
        }
        val compactedHistory = MobileAgentCompactionPolicy.buildSourceContext(
            compactableMessages,
            LOOP_COMPACTION_INPUT_CHARS,
        )
        if (compactedHistory.isBlank()) return messages

        val summary = try {
            val response = requestSimple(
                config = config,
                messages = JSONArray()
                    .put(
                        JSONObject()
                            .put("role", "system")
                            .put(
                                "content",
                                "你负责压缩正在执行中的 Agent 工作历史。必须保留用户目标、后续纠正、已确认事实、关键路径和符号、成功/失败操作、当前进度和下一步；删除冗长工具输出和重复尝试，不要编造。\n\n${MobileAgentCompactionPolicy.outputContract()}",
                            ),
                    )
                    .put(
                        JSONObject()
                            .put("role", "user")
                            .put("content", compactedHistory),
                    ),
                maxTokens = LOOP_COMPACTION_RESPONSE_TOKENS,
            )
            parseContent(parseMessage(response)).take(MAX_SUMMARY_CHARS)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return messages
        }
        if (MobileAgentCompactionPolicy.generatedSummaryError(summary, compactedHistory) != null) return messages

        return buildCompactedLoopMessages(messages, summary, keepStart)
    }

    internal fun buildCompactedLoopMessagesForTesting(
        messages: JSONArray,
        summary: String,
    ): JSONArray = buildCompactedLoopMessages(messages, summary, recentContextStart(messages))

    private fun buildCompactedLoopMessages(
        messages: JSONArray,
        summary: String,
        keepStart: Int,
    ): JSONArray = JSONArray().apply {
        put(messages.getJSONObject(0))
        // Some OpenAI-compatible providers reject a tool history whose first non-system
        // message is an assistant tool call. Keep the compressed working memory as a user
        // anchor so the retained assistant/tool groups remain a valid continuation.
        put(
            JSONObject()
                .put("role", "user")
                .put(
                    "content",
                    "Working memory summary of earlier steps in this Agent run:\n$summary\n\nContinue the task from this state.",
                ),
        )
        for (index in keepStart until messages.length()) {
            put(messages.getJSONObject(index))
        }
    }

    private fun estimateProtocolChars(messages: JSONArray): Int {
        var total = 0L
        for (index in 0 until messages.length()) {
            total += messages.optJSONObject(index)?.toString()?.length ?: 0
            if (total >= Int.MAX_VALUE) return Int.MAX_VALUE
        }
        return total.toInt()
    }

    private fun recentContextStart(messages: JSONArray): Int {
        var start = maxOf(1, messages.length() - LOOP_RECENT_MESSAGE_COUNT)
        while (start > 1 && messages.optJSONObject(start)?.optString("role") == "tool") start--
        return start
    }

    suspend fun summarizeForCompaction(
        config: LlmProviderConfig,
        existingSummary: String?,
        messages: List<MobileAgentMessage>,
    ): String = withContext(Dispatchers.IO) {
        require(messages.isNotEmpty()) { "没有可压缩的会话历史" }
        cancelled = false
        val validated = config.validated()
        val history = MobileAgentCompactionPolicy.buildSourceContext(messages, MAX_COMPACTION_INPUT_CHARS)
        val prompt = buildString {
            appendLine("将下面的 Agent 会话压缩成可供后续模型继续工作的长期上下文摘要。")
            appendLine("必须保留：用户目标、关键约束、已确认事实、重要文件路径、执行过的关键操作、当前进度、未完成事项。")
            appendLine("删除：寒暄、重复信息、完整工具输出和无用中间步骤。不要编造。")
            appendLine()
            appendLine(MobileAgentCompactionPolicy.outputContract())
            existingSummary?.takeIf(String::isNotBlank)?.let {
                appendLine()
                appendLine("已有摘要：")
                appendLine(it.take(MAX_EXISTING_SUMMARY_CHARS))
            }
            appendLine()
            appendLine("新增历史：")
            append(history)
        }
        val response = requestSimple(
            config = validated,
            messages = JSONArray()
                .put(JSONObject().put("role", "system").put("content", "你负责压缩长期 Agent 会话上下文。输出紧凑、事实化的中文摘要。"))
                .put(JSONObject().put("role", "user").put("content", prompt)),
            maxTokens = COMPACTION_RESPONSE_TOKENS,
        )
        val responseJson = JSONObject(response)
        val finishReason = responseJson.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optString("finish_reason")
            .orEmpty()
        val summary = parseContent(parseMessage(response)).take(MAX_SUMMARY_CHARS)
        val validationError = MobileAgentCompactionPolicy.generatedSummaryError(summary, history)
        require(validationError == null) {
            "上下文压缩结果无效：$validationError；finish_reason=${finishReason.ifBlank { "unknown" }}；响应片段=${summary.take(240)}"
        }
        summary
    }

    internal fun buildProtocolMessagesForTesting(
        systemPrompt: String,
        conversation: MobileAgentConversation,
    ): JSONArray = buildProtocolMessages(systemPrompt, conversation)

    private fun buildProtocolMessages(
        systemPrompt: String,
        conversation: MobileAgentConversation,
    ): JSONArray {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt.take(MAX_MESSAGE_CHARS)))
        conversation.summary?.takeIf(MobileAgentCompactionPolicy::isUsablePersistedSummary)?.let { summary ->
            messages.put(
                JSONObject()
                    .put("role", "system")
                    .put("content", "Earlier conversation summary:\n${summary.take(MAX_SUMMARY_CHARS)}"),
            )
        }
        val startIndex = conversation.summaryThroughMessageId
            ?.takeIf { MobileAgentCompactionPolicy.isUsablePersistedSummary(conversation.summary) }
            ?.let { boundary ->
            conversation.messages.indexOfFirst { it.id == boundary }.takeIf { it >= 0 }?.plus(1)
        } ?: 0
        conversation.messages.drop(startIndex).forEach { message -> messages.put(message.toOpenAiJson()) }
        return messages
    }

    private fun MobileAgentMessage.toOpenAiJson(): JSONObject = when (role) {
        MobileAgentRole.USER -> JSONObject()
            .put("role", "user")
            .put("content", userContentWithAttachments().take(MAX_MESSAGE_CHARS))
        MobileAgentRole.ASSISTANT -> JSONObject()
            .put("role", "assistant")
            .put("content", content.take(MAX_MESSAGE_CHARS).takeIf(String::isNotBlank) ?: JSONObject.NULL)
            .apply {
                toolCallsJson?.takeIf(String::isNotBlank)?.let { put("tool_calls", JSONArray(it)) }
            }
        MobileAgentRole.TOOL -> JSONObject()
            .put("role", "tool")
            .put("tool_call_id", requireNotNull(toolCallId) { "tool message 缺少 toolCallId" })
            .put("content", content.take(MAX_MODEL_TOOL_RESULT_CHARS))
            .apply { toolName?.let { put("name", it) } }
    }

    private fun MobileAgentMessage.userContentWithAttachments(): String = buildString {
        append(content)
        if (attachments.isNotEmpty()) {
            if (isNotEmpty()) append("\n\n")
            appendLine("Attached files available in the current workspace:")
            attachments.forEach { attachment ->
                append("- ").append(attachment.displayName)
                    .append(" -> ").append(attachment.agentPath)
                    .append(" (size=").append(attachment.sizeBytes)
                attachment.mimeType?.let { append(", mime=").append(it) }
                appendLine(")")
            }
        }
    }

    private fun requestStreamingMessage(
        config: LlmProviderConfig,
        messages: JSONArray,
        tools: JSONArray,
        onTextSnapshot: (String) -> Unit,
    ): JSONObject = when (LlmEndpointNormalizer.protocol(config.baseUrl)) {
        LlmApiProtocol.OPENAI_CHAT -> requestOpenAiStreamingMessage(config, messages, tools, onTextSnapshot)
        LlmApiProtocol.ANTHROPIC_MESSAGES -> requestAnthropicStreamingMessage(config, messages, tools, onTextSnapshot)
    }

    private fun requestOpenAiStreamingMessage(
        config: LlmProviderConfig,
        messages: JSONArray,
        tools: JSONArray,
        onTextSnapshot: (String) -> Unit,
    ): JSONObject {
        val connection = openConnection(config)
        activeConnection = connection
        try {
            val request = JSONObject()
                .put("model", config.model)
                .put("temperature", 0.1)
                .put("max_tokens", MAX_RESPONSE_TOKENS)
                .put("messages", messages)
                .put("tools", tools)
                .put("tool_choice", "auto")
                .put("stream", true)
            writeRequest(connection, request)
            val status = connection.responseCode
            val input = if (status in 200..299) connection.inputStream else connection.errorStream
            if (status !in 200..299) {
                val responseText = readLimited(input, MAX_RESPONSE_CHARS)
                throw IOException("外部模型 tool request 失败：HTTP $status，${responseText.take(MAX_ERROR_CHARS)}")
            }
            val contentType = connection.contentType.orEmpty().lowercase()
            if (!contentType.contains("text/event-stream")) {
                return parseMessage(readLimited(input, MAX_RESPONSE_CHARS))
            }
            return readStreamingAssistant(input, onTextSnapshot)
        } catch (error: IOException) {
            if (cancelled) throw CancellationException("Agent request cancelled").also { it.initCause(error) }
            throw error
        } finally {
            if (activeConnection === connection) activeConnection = null
            connection.disconnect()
        }
    }

    private fun requestSimple(
        config: LlmProviderConfig,
        messages: JSONArray,
        maxTokens: Int,
    ): String = when (LlmEndpointNormalizer.protocol(config.baseUrl)) {
        LlmApiProtocol.OPENAI_CHAT -> requestOpenAiSimple(config, messages, maxTokens)
        LlmApiProtocol.ANTHROPIC_MESSAGES -> requestAnthropicSimple(config, messages, maxTokens)
    }

    private fun requestOpenAiSimple(
        config: LlmProviderConfig,
        messages: JSONArray,
        maxTokens: Int,
    ): String {
        val connection = openConnection(config)
        activeConnection = connection
        try {
            val request = JSONObject()
                .put("model", config.model)
                .put("temperature", 0.1)
                .put("max_tokens", maxTokens)
                .put("messages", messages)
                .put("stream", false)
            writeRequest(connection, request)
            val status = connection.responseCode
            val responseText = readLimited(
                if (status in 200..299) connection.inputStream else connection.errorStream,
                MAX_RESPONSE_CHARS,
            )
            if (status !in 200..299) {
                throw IOException("外部模型 request 失败：HTTP $status，${responseText.take(MAX_ERROR_CHARS)}")
            }
            return responseText
        } catch (error: IOException) {
            if (cancelled) throw CancellationException("Agent request cancelled").also { it.initCause(error) }
            throw error
        } finally {
            if (activeConnection === connection) activeConnection = null
            connection.disconnect()
        }
    }

    private fun requestAnthropicStreamingMessage(
        config: LlmProviderConfig,
        messages: JSONArray,
        tools: JSONArray,
        onTextSnapshot: (String) -> Unit,
    ): JSONObject {
        val connection = openConnection(config)
        activeConnection = connection
        try {
            val request = AnthropicMessagesAdapter.buildRequest(
                config = config,
                canonicalMessages = messages,
                tools = tools,
                maxTokens = MAX_RESPONSE_TOKENS,
                stream = true,
            )
            writeRequest(connection, request)
            val status = connection.responseCode
            val input = if (status in 200..299) connection.inputStream else connection.errorStream
            if (status !in 200..299) {
                val responseText = readLimited(input, MAX_RESPONSE_CHARS)
                throw IOException("Anthropic tool request 失败：HTTP $status，${responseText.take(MAX_ERROR_CHARS)}")
            }
            val contentType = connection.contentType.orEmpty().lowercase()
            if (!contentType.contains("text/event-stream")) {
                return AnthropicMessagesAdapter.parseResponse(readLimited(input, MAX_RESPONSE_CHARS)).message
            }
            return readAnthropicStreamingAssistant(input, onTextSnapshot)
        } catch (error: IOException) {
            if (cancelled) throw CancellationException("Agent request cancelled").also { it.initCause(error) }
            throw error
        } finally {
            if (activeConnection === connection) activeConnection = null
            connection.disconnect()
        }
    }

    private fun requestAnthropicSimple(
        config: LlmProviderConfig,
        messages: JSONArray,
        maxTokens: Int,
    ): String {
        val connection = openConnection(config)
        activeConnection = connection
        try {
            val request = AnthropicMessagesAdapter.buildRequest(
                config = config,
                canonicalMessages = messages,
                maxTokens = maxTokens,
                stream = false,
            )
            writeRequest(connection, request)
            val status = connection.responseCode
            val responseText = readLimited(
                if (status in 200..299) connection.inputStream else connection.errorStream,
                MAX_RESPONSE_CHARS,
            )
            if (status !in 200..299) {
                throw IOException("Anthropic request 失败：HTTP $status，${responseText.take(MAX_ERROR_CHARS)}")
            }
            return AnthropicMessagesAdapter.wrapAsCanonicalChatResponse(
                AnthropicMessagesAdapter.parseResponse(responseText),
            )
        } catch (error: IOException) {
            if (cancelled) throw CancellationException("Agent request cancelled").also { it.initCause(error) }
            throw error
        } finally {
            if (activeConnection === connection) activeConnection = null
            connection.disconnect()
        }
    }

    private fun openConnection(config: LlmProviderConfig): HttpURLConnection =
        (URL(config.baseUrl).openConnection() as? HttpURLConnection
            ?: throw IOException("外部模型地址不是 HTTP(S) 连接")).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "text/event-stream, application/json")
            when (LlmEndpointNormalizer.protocol(config.baseUrl)) {
                LlmApiProtocol.OPENAI_CHAT -> setRequestProperty("Authorization", "Bearer ${config.apiKey}")
                LlmApiProtocol.ANTHROPIC_MESSAGES -> {
                    // MiniMax's Anthropic-compatible endpoint follows Claude Code's auth-token mode.
                    // Also send x-api-key for compatibility with standard Anthropic gateways.
                    setRequestProperty("Authorization", "Bearer ${config.apiKey}")
                    setRequestProperty("x-api-key", config.apiKey)
                    setRequestProperty("anthropic-version", ANTHROPIC_VERSION)
                }
            }
        }

    private fun writeRequest(connection: HttpURLConnection, request: JSONObject) {
        ensureRunningBlocking()
        connection.outputStream.use { output ->
            output.write(request.toString().toByteArray(Charsets.UTF_8))
        }
    }

    private fun readStreamingAssistant(
        input: InputStream?,
        onTextSnapshot: (String) -> Unit,
    ): JSONObject {
        requireNotNull(input) { "模型流式响应为空" }
        val content = StringBuilder()
        val toolCalls = sortedMapOf<Int, StreamingToolCall>()
        var lastSnapshotAtNanos = 0L
        var lastSnapshotLength = 0
        fun publishTextSnapshot(force: Boolean = false) {
            if (content.length == lastSnapshotLength) return
            val now = System.nanoTime()
            if (
                !force &&
                content.length - lastSnapshotLength < STREAMING_SNAPSHOT_CHAR_STEP &&
                now - lastSnapshotAtNanos < STREAMING_SNAPSHOT_INTERVAL_NANOS
            ) return
            onTextSnapshot(content.toString())
            lastSnapshotLength = content.length
            lastSnapshotAtNanos = now
        }
        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
            while (true) {
                ensureRunningBlocking()
                val line = reader.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isBlank() || payload == "[DONE]") continue
                val root = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                val delta = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: continue
                parseDeltaContent(delta.opt("content")).takeIf(String::isNotEmpty)?.let { piece ->
                    content.append(piece)
                    if (content.length > MAX_ANSWER_CHARS) content.setLength(MAX_ANSWER_CHARS)
                    publishTextSnapshot()
                }
                val deltaCalls = delta.optJSONArray("tool_calls") ?: continue
                for (index in 0 until deltaCalls.length()) {
                    val part = deltaCalls.optJSONObject(index) ?: continue
                    val callIndex = part.optInt("index", index)
                    val accumulator = toolCalls.getOrPut(callIndex) { StreamingToolCall() }
                    part.optString("id").takeIf(String::isNotBlank)?.let { accumulator.id = it }
                    val function = part.optJSONObject("function")
                    function?.optString("name")?.takeIf(String::isNotBlank)?.let { name ->
                        if (accumulator.name.isBlank()) accumulator.name = name else if (!accumulator.name.endsWith(name)) accumulator.name += name
                    }
                    function?.optString("arguments")?.takeIf(String::isNotEmpty)?.let(accumulator.arguments::append)
                }
            }
        }
        publishTextSnapshot(force = true)
        val result = JSONObject()
            .put("role", "assistant")
            .put("content", content.toString().ifBlank { JSONObject.NULL })
        if (toolCalls.isNotEmpty()) {
            result.put(
                "tool_calls",
                JSONArray().apply {
                    toolCalls.values.forEach { call ->
                        require(call.id.isNotBlank() && call.name.isNotBlank()) { "流式 tool call 不完整" }
                        put(
                            JSONObject()
                                .put("id", call.id.take(MAX_CALL_ID_CHARS))
                                .put("type", "function")
                                .put(
                                    "function",
                                    JSONObject()
                                        .put("name", call.name)
                                        .put("arguments", call.arguments.toString().take(MAX_ARGUMENT_CHARS)),
                                ),
                        )
                    }
                },
            )
        }
        return result
    }

    private fun readAnthropicStreamingAssistant(
        input: InputStream?,
        onTextSnapshot: (String) -> Unit,
    ): JSONObject {
        requireNotNull(input) { "Anthropic 模型流式响应为空" }
        val accumulator = AnthropicMessagesAdapter.StreamAccumulator()
        var lastSnapshotAtNanos = 0L
        var lastSnapshotLength = 0
        fun publishTextSnapshot(force: Boolean = false) {
            val text = accumulator.text()
            if (text.length == lastSnapshotLength) return
            val now = System.nanoTime()
            if (
                !force &&
                text.length - lastSnapshotLength < STREAMING_SNAPSHOT_CHAR_STEP &&
                now - lastSnapshotAtNanos < STREAMING_SNAPSHOT_INTERVAL_NANOS
            ) return
            onTextSnapshot(text.take(MAX_ANSWER_CHARS))
            lastSnapshotLength = text.length
            lastSnapshotAtNanos = now
        }
        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
            while (true) {
                ensureRunningBlocking()
                val line = reader.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val payloadText = line.removePrefix("data:").trim()
                if (payloadText.isBlank() || payloadText == "[DONE]") continue
                val payload = runCatching { JSONObject(payloadText) }.getOrNull() ?: continue
                accumulator.consume(payload)
                publishTextSnapshot()
            }
        }
        publishTextSnapshot(force = true)
        return accumulator.toCanonicalMessage()
    }

    private fun parseDeltaContent(value: Any?): String = when (value) {
        is String -> value
        is JSONArray -> buildString {
            for (index in 0 until value.length()) {
                val item = value.optJSONObject(index) ?: continue
                append(item.optString("text"))
            }
        }
        else -> ""
    }

    private fun normalizeAssistantToolMessage(message: JSONObject): JSONObject = JSONObject()
        .put("role", "assistant")
        .put("content", message.opt("content") ?: JSONObject.NULL)
        .put("tool_calls", message.getJSONArray("tool_calls"))

    private fun parseMessage(responseText: String): JSONObject {
        val root = JSONObject(responseText)
        return root.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?: throw IOException("外部模型 response 缺少 choices[0].message")
    }

    private fun parseContent(message: JSONObject): String {
        val value = message.opt("content")
        return when (value) {
            is String -> value.trim()
            is JSONArray -> buildString {
                for (index in 0 until value.length()) {
                    val item = value.optJSONObject(index) ?: continue
                    val text = item.optString("text")
                    if (text.isNotBlank()) {
                        if (isNotEmpty()) append('\n')
                        append(text)
                    }
                }
            }.trim()
            else -> ""
        }
    }

    private suspend fun ensureRunning() {
        currentCoroutineContext().ensureActive()
        if (cancelled) throw CancellationException("Agent request cancelled")
    }

    private fun ensureRunningBlocking() {
        if (cancelled) throw CancellationException("Agent request cancelled")
    }

    private fun readLimited(input: InputStream?, maxChars: Int): String {
        if (input == null) return ""
        val builder = StringBuilder()
        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
            val buffer = CharArray(4_096)
            while (builder.length < maxChars) {
                ensureRunningBlocking()
                val count = reader.read(buffer, 0, minOf(buffer.size, maxChars - builder.length))
                if (count < 0) break
                builder.append(buffer, 0, count)
            }
        }
        return builder.toString()
    }

    private data class StreamingToolCall(
        var id: String = "",
        var name: String = "",
        val arguments: StringBuilder = StringBuilder(),
    )

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 30_000
        const val READ_TIMEOUT_MILLIS = 180_000
        const val ANTHROPIC_VERSION = "2023-06-01"
        const val MAX_RESPONSE_TOKENS = 4_096
        const val COMPACTION_RESPONSE_TOKENS = 1_500
        const val MAX_RESPONSE_CHARS = 320_000
        const val MAX_ERROR_CHARS = 2_000
        const val MAX_ANSWER_CHARS = 100_000
        const val MAX_MESSAGE_CHARS = 80_000
        const val MAX_TOOL_RESULT_CHARS = 40_000
        const val MAX_MODEL_TOOL_RESULT_CHARS = 16_000
        const val MAX_ARGUMENT_CHARS = 12_000
        const val MAX_CALL_ID_CHARS = 256
        const val DEFAULT_MAX_TOOL_ROUNDS = 0
        const val ABSOLUTE_MAX_TOOL_ROUNDS = 2_048
        const val LOOP_COMPACTION_TRIGGER_CHARS = 320_000
        const val LOOP_RECENT_MESSAGE_COUNT = 16
        const val LOOP_COMPACTION_INPUT_CHARS = 80_000
        const val LOOP_COMPACTION_RESPONSE_TOKENS = 2_000
        const val STREAMING_SNAPSHOT_CHAR_STEP = 256
        const val STREAMING_SNAPSHOT_INTERVAL_NANOS = 100_000_000L
        const val MAX_TOOL_CALLS_PER_ROUND = 8
        const val MAX_IDENTICAL_TOOL_REQUESTS = 3
        const val MAX_COMPACTION_INPUT_CHARS = 60_000
        const val MAX_EXISTING_SUMMARY_CHARS = 20_000
        const val MAX_SUMMARY_CHARS = 24_000
    }
}

data class MobileAgentCompletion(
    val model: String,
    val endpointHost: String,
    val content: String,
    val toolExecutionCount: Int,
    val startedAtEpochMillis: Long,
    val completedAtEpochMillis: Long,
) {
    val durationMillis: Long
        get() = (completedAtEpochMillis - startedAtEpochMillis).coerceAtLeast(0L)
}
