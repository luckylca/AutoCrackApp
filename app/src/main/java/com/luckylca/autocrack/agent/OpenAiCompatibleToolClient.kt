package com.luckylca.autocrack.agent

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** OpenAI-compatible chat-completions loop with bounded function/tool calling. */
class OpenAiCompatibleToolClient {
    suspend fun completeWithTools(
        config: LlmProviderConfig,
        systemPrompt: String,
        userPrompt: String,
        tools: List<AgentToolDefinition>,
        dispatcher: AgentToolDispatcher,
    ): LlmToolAgentAnswer = withContext(Dispatchers.IO) {
        require(tools.isNotEmpty()) { "Agent tool list must not be empty" }
        val validated = config.validated()
        val startedAt = System.currentTimeMillis()
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt.take(MAX_MESSAGE_CHARS)))
            .put(JSONObject().put("role", "user").put("content", userPrompt.take(MAX_MESSAGE_CHARS)))
        val toolJson = JSONArray().also { array -> tools.forEach { array.put(it.toOpenAiJson()) } }
        val knownTools = tools.associateBy(AgentToolDefinition::name)
        val executions = mutableListOf<AgentToolExecutionRecord>()

        repeat(MAX_TOOL_ROUNDS) {
            val responseText = request(validated, messages, toolJson)
            val message = parseMessage(responseText)
            val calls = message.optJSONArray("tool_calls")
            if (calls == null || calls.length() == 0) {
                val content = parseContent(message)
                if (content.isBlank()) throw IOException("模型结束 tool loop 时返回了空答案")
                return@withContext LlmToolAgentAnswer(
                    model = validated.model,
                    endpointHost = URI(validated.baseUrl).host.orEmpty(),
                    content = content.take(MAX_ANSWER_CHARS),
                    toolExecutions = executions.toList(),
                    startedAtEpochMillis = startedAt,
                    completedAtEpochMillis = System.currentTimeMillis(),
                )
            }

            require(calls.length() <= MAX_TOOL_CALLS_PER_ROUND) {
                "模型单轮请求了过多工具：${calls.length()} > $MAX_TOOL_CALLS_PER_ROUND"
            }
            messages.put(normalizeAssistantToolMessage(message))

            for (index in 0 until calls.length()) {
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
                val result = runCatching { dispatcher(name, arguments) }
                    .getOrElse { error ->
                        JSONObject()
                            .put("ok", false)
                            .put("error", (error.message ?: error::class.java.simpleName).take(MAX_TOOL_RESULT_CHARS))
                            .toString()
                    }
                    .take(MAX_TOOL_RESULT_CHARS)
                executions += AgentToolExecutionRecord(
                    callId = callId,
                    toolName = name,
                    argumentsJson = arguments.toString().take(MAX_ARGUMENT_CHARS),
                    resultJson = result,
                )
                messages.put(
                    JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", callId)
                        .put("content", result),
                )
            }
        }

        throw IOException("Agent tool loop 超过 $MAX_TOOL_ROUNDS 轮，已安全停止")
    }

    private fun request(
        config: LlmProviderConfig,
        messages: JSONArray,
        tools: JSONArray,
    ): String {
        val connection = URL(config.baseUrl).openConnection() as? HttpsURLConnection
            ?: throw IOException("外部模型地址不是 HTTPS 连接")
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            val request = JSONObject()
                .put("model", config.model)
                .put("temperature", 0.1)
                .put("max_tokens", MAX_RESPONSE_TOKENS)
                .put("messages", messages)
                .put("tools", tools)
                .put("tool_choice", "auto")
            connection.outputStream.use { output ->
                output.write(request.toString().toByteArray(Charsets.UTF_8))
            }
            val status = connection.responseCode
            val responseText = readLimited(
                if (status in 200..299) connection.inputStream else connection.errorStream,
                MAX_RESPONSE_CHARS,
            )
            if (status !in 200..299) {
                throw IOException("外部模型 tool request 失败：HTTP $status，${responseText.take(MAX_ERROR_CHARS)}")
            }
            return responseText
        } finally {
            connection.disconnect()
        }
    }

    private fun parseMessage(responseText: String): JSONObject {
        val root = JSONObject(responseText)
        return root.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?: throw IOException("外部模型 tool response 缺少 choices[0].message")
    }

    private fun normalizeAssistantToolMessage(message: JSONObject): JSONObject {
        val normalized = JSONObject()
            .put("role", "assistant")
            .put("content", message.opt("content") ?: JSONObject.NULL)
        normalized.put("tool_calls", message.getJSONArray("tool_calls"))
        return normalized
    }

    private fun parseContent(message: JSONObject): String {
        val content = message.opt("content")
        return when (content) {
            is String -> content.trim()
            is JSONArray -> buildString {
                for (index in 0 until content.length()) {
                    val item = content.optJSONObject(index) ?: continue
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

    private fun readLimited(input: InputStream?, maxChars: Int): String {
        if (input == null) return ""
        val builder = StringBuilder()
        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
            val buffer = CharArray(4_096)
            while (builder.length < maxChars) {
                val count = reader.read(buffer, 0, minOf(buffer.size, maxChars - builder.length))
                if (count < 0) break
                builder.append(buffer, 0, count)
            }
        }
        return builder.toString()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 30_000
        const val READ_TIMEOUT_MILLIS = 120_000
        const val MAX_RESPONSE_TOKENS = 2_000
        const val MAX_RESPONSE_CHARS = 240_000
        const val MAX_ERROR_CHARS = 2_000
        const val MAX_ANSWER_CHARS = 80_000
        const val MAX_MESSAGE_CHARS = 60_000
        const val MAX_TOOL_RESULT_CHARS = 30_000
        const val MAX_ARGUMENT_CHARS = 8_000
        const val MAX_CALL_ID_CHARS = 256
        const val MAX_TOOL_ROUNDS = 10
        const val MAX_TOOL_CALLS_PER_ROUND = 8
    }
}
