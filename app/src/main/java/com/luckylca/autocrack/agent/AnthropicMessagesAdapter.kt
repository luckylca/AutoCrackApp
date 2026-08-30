package com.luckylca.autocrack.agent

import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

/** Converts the Agent's canonical OpenAI-style tool history to/from Anthropic Messages API. */
internal object AnthropicMessagesAdapter {
    data class ParsedResponse(
        val message: JSONObject,
        val stopReason: String,
    )

    fun buildTools(tools: List<AgentToolDefinition>): JSONArray =
        JSONArray().also { array -> tools.forEach { array.put(it.toAnthropicJson()) } }

    fun buildRequest(
        config: LlmProviderConfig,
        canonicalMessages: JSONArray,
        tools: JSONArray? = null,
        maxTokens: Int,
        stream: Boolean,
    ): JSONObject {
        val systemParts = mutableListOf<String>()
        val messages = JSONArray()
        for (index in 0 until canonicalMessages.length()) {
            val message = canonicalMessages.optJSONObject(index) ?: continue
            when (message.optString("role")) {
                "system" -> message.optString("content").takeIf(String::isNotBlank)?.let(systemParts::add)
                "user" -> appendMessage(messages, "user", JSONArray().put(textBlock(message.optString("content"))))
                "assistant" -> appendMessage(messages, "assistant", assistantBlocks(message))
                "tool" -> appendMessage(
                    messages,
                    "user",
                    JSONArray().put(
                        JSONObject()
                            .put("type", "tool_result")
                            .put("tool_use_id", message.getString("tool_call_id"))
                            .put("content", message.optString("content")),
                    ),
                )
            }
        }
        val request = JSONObject()
            .put("model", config.model)
            .put("max_tokens", maxTokens)
            .put("messages", messages)
            .put("stream", stream)
            .put("temperature", 0.1)
        if (systemParts.isNotEmpty()) request.put("system", systemParts.joinToString("\n\n"))
        if (tools != null && tools.length() > 0) {
            request.put("tools", tools)
            request.put("tool_choice", JSONObject().put("type", "auto"))
        }
        return request
    }

    fun parseResponse(responseText: String): ParsedResponse = parseResponse(JSONObject(responseText))

    fun parseResponse(root: JSONObject): ParsedResponse {
        root.optJSONObject("error")?.let { error ->
            throw IOException("Anthropic 模型返回错误：${error.toString().take(2_000)}")
        }
        val content = StringBuilder()
        val calls = JSONArray()
        val blocks = root.optJSONArray("content") ?: JSONArray()
        for (index in 0 until blocks.length()) {
            val block = blocks.optJSONObject(index) ?: continue
            when (block.optString("type")) {
                "text" -> block.optString("text").takeIf(String::isNotEmpty)?.let(content::append)
                "tool_use" -> calls.put(
                    canonicalToolCall(
                        id = block.getString("id"),
                        name = block.getString("name"),
                        arguments = (block.optJSONObject("input") ?: JSONObject()).toString(),
                    ),
                )
            }
        }
        return ParsedResponse(
            message = canonicalAssistant(content.toString(), calls),
            stopReason = root.optString("stop_reason"),
        )
    }

    fun wrapAsCanonicalChatResponse(parsed: ParsedResponse): String = JSONObject()
        .put(
            "choices",
            JSONArray().put(
                JSONObject()
                    .put("message", parsed.message)
                    .put("finish_reason", parsed.stopReason),
            ),
        )
        .toString()

    class StreamAccumulator {
        private val content = StringBuilder()
        private val calls = sortedMapOf<Int, StreamingToolCall>()

        fun consume(payload: JSONObject): String? {
            payload.optJSONObject("error")?.let { error ->
                throw IOException("Anthropic 流式响应错误：${error.toString().take(2_000)}")
            }
            return when (payload.optString("type")) {
                "content_block_start" -> {
                    val index = payload.optInt("index", 0)
                    val block = payload.optJSONObject("content_block") ?: return null
                    when (block.optString("type")) {
                        "text" -> block.optString("text").takeIf(String::isNotEmpty)?.also(content::append)
                        "tool_use" -> {
                            val call = calls.getOrPut(index) { StreamingToolCall() }
                            call.id = block.optString("id")
                            call.name = block.optString("name")
                            val initialInput = block.optJSONObject("input")
                            if (initialInput != null && initialInput.length() > 0) {
                                call.arguments.append(initialInput.toString())
                            }
                            null
                        }
                        else -> null
                    }
                }
                "content_block_delta" -> {
                    val index = payload.optInt("index", 0)
                    val delta = payload.optJSONObject("delta") ?: return null
                    when (delta.optString("type")) {
                        "text_delta" -> delta.optString("text").takeIf(String::isNotEmpty)?.also(content::append)
                        "input_json_delta" -> {
                            calls.getOrPut(index) { StreamingToolCall() }
                                .arguments
                                .append(delta.optString("partial_json"))
                            null
                        }
                        else -> null
                    }
                }
                else -> null
            }
        }

        fun text(): String = content.toString()

        fun toCanonicalMessage(): JSONObject {
            val array = JSONArray()
            calls.values.forEach { call ->
                require(call.id.isNotBlank() && call.name.isNotBlank()) { "Anthropic tool_use 不完整" }
                val arguments = call.arguments.toString().ifBlank { "{}" }
                runCatching { JSONObject(arguments) }.getOrElse {
                    throw IOException("Anthropic tool_use input 不是有效 JSON：${arguments.take(500)}", it)
                }
                array.put(canonicalToolCall(call.id, call.name, arguments))
            }
            return canonicalAssistant(content.toString(), array)
        }
    }

    private fun assistantBlocks(message: JSONObject): JSONArray = JSONArray().apply {
        message.optString("content").takeIf(String::isNotBlank)?.let { put(textBlock(it)) }
        val calls = message.optJSONArray("tool_calls") ?: JSONArray()
        for (index in 0 until calls.length()) {
            val call = calls.optJSONObject(index) ?: continue
            val function = call.optJSONObject("function") ?: continue
            val rawArguments = function.optString("arguments", "{}").ifBlank { "{}" }
            val input = runCatching { JSONObject(rawArguments) }.getOrElse {
                JSONObject().put("_raw_arguments", rawArguments)
            }
            put(
                JSONObject()
                    .put("type", "tool_use")
                    .put("id", call.getString("id"))
                    .put("name", function.getString("name"))
                    .put("input", input),
            )
        }
    }

    private fun appendMessage(messages: JSONArray, role: String, blocks: JSONArray) {
        if (blocks.length() == 0) return
        val previous = messages.optJSONObject(messages.length() - 1)
        if (previous != null && previous.optString("role") == role) {
            val content = previous.optJSONArray("content") ?: JSONArray().also { previous.put("content", it) }
            for (index in 0 until blocks.length()) content.put(blocks.get(index))
        } else {
            messages.put(JSONObject().put("role", role).put("content", blocks))
        }
    }

    private fun textBlock(text: String): JSONObject = JSONObject()
        .put("type", "text")
        .put("text", text)

    private fun canonicalAssistant(content: String, calls: JSONArray): JSONObject = JSONObject()
        .put("role", "assistant")
        .put("content", content.takeIf(String::isNotBlank) ?: JSONObject.NULL)
        .apply { if (calls.length() > 0) put("tool_calls", calls) }

    private fun canonicalToolCall(id: String, name: String, arguments: String): JSONObject = JSONObject()
        .put("id", id)
        .put("type", "function")
        .put(
            "function",
            JSONObject()
                .put("name", name)
                .put("arguments", arguments),
        )

    private data class StreamingToolCall(
        var id: String = "",
        var name: String = "",
        val arguments: StringBuilder = StringBuilder(),
    )
}
