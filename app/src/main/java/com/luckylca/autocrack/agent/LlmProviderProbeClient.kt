package com.luckylca.autocrack.agent

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class LlmConnectivityResult(
    val statusCode: Int,
    val endpoint: String,
)

data class LlmHiTestResult(
    val responseText: String,
)

class LlmProviderProbeClient(
    private val connectionFactory: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as? HttpURLConnection
            ?: throw IOException("模型地址不是 HTTP(S) 连接")
    },
) {
    suspend fun fetchModels(config: LlmProviderConfig): List<String> = withContext(Dispatchers.IO) {
        val prepared = prepareForProbe(config)
        val endpoint = LlmEndpointNormalizer.modelsEndpoint(prepared)
        executeModelsRequest(prepared, endpoint, parseModels = true).models
    }

    suspend fun testConnectivity(config: LlmProviderConfig): LlmConnectivityResult = withContext(Dispatchers.IO) {
        val prepared = prepareForProbe(config)
        val endpoint = LlmEndpointNormalizer.modelsEndpoint(prepared)
        val response = executeModelsRequest(prepared, endpoint, parseModels = false)
        LlmConnectivityResult(response.statusCode, endpoint)
    }

    suspend fun testHi(config: LlmProviderConfig): LlmHiTestResult = withContext(Dispatchers.IO) {
        val validated = config.validated()
        val connection = openConnection(
            url = validated.baseUrl,
            method = "POST",
            protocol = validated.protocol,
            apiKey = validated.apiKey,
            output = true,
        )
        try {
            val request = when (validated.protocol) {
                LlmApiProtocol.OPENAI_CHAT -> JSONObject()
                    .put("model", validated.model)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "hi")))
                    .put("max_tokens", 16)
                    .put("stream", false)
                LlmApiProtocol.ANTHROPIC_MESSAGES -> JSONObject()
                    .put("model", validated.model)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "hi")))
                    .put("max_tokens", 16)
                    .put("stream", false)
            }
            connection.outputStream.use { output -> output.write(request.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val response = readResponse(connection, status)
            if (status !in 200..299) throw responseException("发送 hi", status, response)
            LlmHiTestResult(LlmHiResponseParser.parse(validated.protocol, response))
        } finally {
            connection.disconnect()
        }
    }

    private fun executeModelsRequest(
        config: LlmProviderConfig,
        endpoint: String,
        parseModels: Boolean,
    ): ModelsResponse {
        val connection = openConnection(
            url = endpoint,
            method = "GET",
            protocol = config.protocol,
            apiKey = config.apiKey,
            output = false,
        )
        try {
            val status = connection.responseCode
            val response = readResponse(connection, status)
            if (status !in 200..299) throw responseException("获取模型列表", status, response)
            return ModelsResponse(
                statusCode = status,
                models = if (parseModels) LlmModelListParser.parse(response) else emptyList(),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun prepareForProbe(config: LlmProviderConfig): LlmProviderConfig {
        require(config.apiKey.trim().length in 4..8_192) { "API Key 不能为空或格式异常" }
        return config.copy(
            baseUrl = LlmEndpointNormalizer.normalize(config.baseUrl, config.protocol),
            apiKey = config.apiKey.trim(),
        )
    }

    private fun openConnection(
        url: String,
        method: String,
        protocol: LlmApiProtocol,
        apiKey: String,
        output: Boolean,
    ): HttpURLConnection = connectionFactory(URL(url)).apply {
        requestMethod = method
        connectTimeout = CONNECT_TIMEOUT_MILLIS
        readTimeout = READ_TIMEOUT_MILLIS
        doOutput = output
        setRequestProperty("Accept", "application/json")
        if (output) setRequestProperty("Content-Type", "application/json; charset=utf-8")
        when (protocol) {
            LlmApiProtocol.OPENAI_CHAT -> setRequestProperty("Authorization", "Bearer $apiKey")
            LlmApiProtocol.ANTHROPIC_MESSAGES -> {
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("x-api-key", apiKey)
                setRequestProperty("anthropic-version", ANTHROPIC_VERSION)
            }
        }
    }

    private fun readResponse(connection: HttpURLConnection, status: Int): String {
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        return stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
            val buffer = CharArray(8_192)
            val result = StringBuilder()
            while (result.length < MAX_RESPONSE_CHARS) {
                val count = reader.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_CHARS - result.length))
                if (count < 0) break
                result.append(buffer, 0, count)
            }
            result.toString()
        }.orEmpty()
    }

    private fun responseException(action: String, status: Int, response: String): IOException {
        val detail = runCatching {
            val root = JSONObject(response)
            root.optJSONObject("error")?.optString("message")
                ?.takeIf(String::isNotBlank)
                ?: root.optString("message").takeIf(String::isNotBlank)
        }.getOrNull() ?: response.take(500).takeIf(String::isNotBlank)
        return IOException("$action 失败（HTTP $status）${detail?.let { "：$it" }.orEmpty()}")
    }

    private data class ModelsResponse(
        val statusCode: Int,
        val models: List<String>,
    )

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 30_000
        const val MAX_RESPONSE_CHARS = 200_000
        const val ANTHROPIC_VERSION = "2023-06-01"
    }
}

internal object LlmModelListParser {
    fun parse(response: String): List<String> {
        val root = JSONObject(response)
        root.optJSONObject("error")?.let { error ->
            throw IOException("模型列表返回错误：${error.optString("message", error.toString()).take(500)}")
        }
        val data = root.optJSONArray("data") ?: throw IOException("模型列表响应缺少 data 数组")
        return buildList {
            for (index in 0 until data.length()) {
                val id = when (val item = data.opt(index)) {
                    is JSONObject -> item.optString("id")
                    is String -> item
                    else -> ""
                }.trim()
                if (id.isNotBlank() && id.length <= 128) add(id)
            }
        }.distinct().sorted()
    }
}

internal object LlmHiResponseParser {
    fun parse(protocol: LlmApiProtocol, response: String): String {
        val root = JSONObject(response)
        root.optJSONObject("error")?.let { error ->
            throw IOException("模型返回错误：${error.optString("message", error.toString()).take(500)}")
        }
        val text = when (protocol) {
            LlmApiProtocol.OPENAI_CHAT -> parseOpenAi(root)
            LlmApiProtocol.ANTHROPIC_MESSAGES -> parseAnthropic(root)
        }.trim()
        if (text.isBlank()) throw IOException("模型返回成功，但没有可读文本")
        return text.take(1_000)
    }

    private fun parseOpenAi(root: JSONObject): String {
        val content = root.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.opt("content")
        return when (content) {
            is String -> content
            is JSONArray -> buildString {
                for (index in 0 until content.length()) {
                    val block = content.optJSONObject(index) ?: continue
                    if (block.optString("type") == "text") append(block.optString("text"))
                }
            }
            else -> ""
        }
    }

    private fun parseAnthropic(root: JSONObject): String = buildString {
        val content = root.optJSONArray("content") ?: JSONArray()
        for (index in 0 until content.length()) {
            val block = content.optJSONObject(index) ?: continue
            if (block.optString("type") == "text") append(block.optString("text"))
        }
    }
}
