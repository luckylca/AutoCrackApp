package com.luckylca.autocrack.agent

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class OpenAiCompatibleClient {
    suspend fun complete(
        config: LlmProviderConfig,
        prompt: String,
        evidenceCount: Int,
    ): LlmAgentAnswer = withContext(Dispatchers.IO) {
        val validated = config.validated()
        val startedAt = System.currentTimeMillis()
        val endpoint = URL(validated.baseUrl)
        val connection = endpoint.openConnection() as? HttpsURLConnection
            ?: throw IOException("外部模型地址不是 HTTPS 连接")

        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer ${validated.apiKey}")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")

            val request = JSONObject()
                .put("model", validated.model)
                .put("temperature", 0.2)
                .put("max_tokens", MAX_RESPONSE_TOKENS)
                .put(
                    "messages",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("role", "system")
                                .put("content", LlmPromptBuilder.SYSTEM_PROMPT),
                        )
                        .put(
                            JSONObject()
                                .put("role", "user")
                                .put("content", prompt),
                        ),
                )

            connection.outputStream.use { output ->
                output.write(request.toString().toByteArray(Charsets.UTF_8))
            }

            val status = connection.responseCode
            val responseText = readLimited(
                if (status in 200..299) connection.inputStream else connection.errorStream,
                MAX_RESPONSE_CHARS,
            )
            if (status !in 200..299) {
                throw IOException("外部模型请求失败：HTTP $status，${responseText.take(MAX_ERROR_CHARS)}")
            }

            val content = parseContent(responseText)
            if (content.isBlank()) {
                throw IOException("外部模型返回了空答案")
            }
            LlmAgentAnswer(
                model = validated.model,
                endpointHost = URI(validated.baseUrl).host.orEmpty(),
                content = content.take(MAX_ANSWER_CHARS),
                requestEvidenceCount = evidenceCount,
                startedAtEpochMillis = startedAt,
                completedAtEpochMillis = System.currentTimeMillis(),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun parseContent(responseText: String): String {
        val root = JSONObject(responseText)
        val choices = root.optJSONArray("choices")
            ?: throw IOException("外部模型响应缺少 choices")
        val message = choices.optJSONObject(0)?.optJSONObject("message")
            ?: throw IOException("外部模型响应缺少 message")
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
        const val MAX_RESPONSE_TOKENS = 1_500
        const val MAX_RESPONSE_CHARS = 200_000
        const val MAX_ERROR_CHARS = 2_000
        const val MAX_ANSWER_CHARS = 80_000
    }
}
