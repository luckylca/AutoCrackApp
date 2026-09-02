package com.luckylca.autocrack.agent

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class LlmProviderProbeParserTest {
    @Test
    fun modelListParser_acceptsObjectAndStringEntriesAndSortsIds() {
        val response = """
            {
              "object": "list",
              "data": [
                {"id": "model-z"},
                "model-a",
                {"id": "model-z"},
                {"display_name": "missing id"}
              ]
            }
        """.trimIndent()

        assertEquals(listOf("model-a", "model-z"), LlmModelListParser.parse(response))
    }

    @Test
    fun hiParser_readsOpenAiText() {
        val response = """
            {"choices":[{"message":{"role":"assistant","content":"Hello"}}]}
        """.trimIndent()

        assertEquals("Hello", LlmHiResponseParser.parse(LlmApiProtocol.OPENAI_CHAT, response))
    }

    @Test
    fun hiParser_readsAnthropicTextBlocks() {
        val response = """
            {"content":[{"type":"text","text":"Hi"},{"type":"text","text":" there"}]}
        """.trimIndent()

        assertEquals("Hi there", LlmHiResponseParser.parse(LlmApiProtocol.ANTHROPIC_MESSAGES, response))
    }

    @Test
    fun fetchModels_usesModelsEndpointAndBearerAuthWithoutPosting() = runBlocking {
        val connection = FakeHttpConnection(
            responseStatus = 200,
            responseBody = """{"data":[{"id":"model-1"}]}""",
        )
        var requestedUrl: URL? = null
        val client = LlmProviderProbeClient { url -> requestedUrl = url; connection }

        val models = client.fetchModels(provider(LlmApiProtocol.OPENAI_CHAT))

        assertEquals(listOf("model-1"), models)
        assertEquals("https://example.com/v1/models", requestedUrl.toString())
        assertEquals("GET", connection.requestMethod)
        assertEquals("Bearer test-key", connection.getRequestProperty("Authorization"))
        assertEquals(0, connection.requestBody.size())
    }

    @Test
    fun testHi_usesAnthropicHeadersAndMessagesPayload() = runBlocking {
        val connection = FakeHttpConnection(
            responseStatus = 200,
            responseBody = """{"content":[{"type":"text","text":"Hello"}]}""",
        )
        var requestedUrl: URL? = null
        val client = LlmProviderProbeClient { url -> requestedUrl = url; connection }

        val result = client.testHi(provider(LlmApiProtocol.ANTHROPIC_MESSAGES))

        assertEquals("Hello", result.responseText)
        assertEquals("https://example.com/v1/messages", requestedUrl.toString())
        assertEquals("POST", connection.requestMethod)
        assertEquals("test-key", connection.getRequestProperty("x-api-key"))
        assertEquals("2023-06-01", connection.getRequestProperty("anthropic-version"))
        assertEquals("hi", org.json.JSONObject(connection.requestBody.toString(Charsets.UTF_8.name()))
            .getJSONArray("messages").getJSONObject(0).getString("content"))
    }

    private fun provider(protocol: LlmApiProtocol): LlmProviderConfig = LlmProviderConfig(
        baseUrl = "https://example.com/v1",
        model = "test-model",
        apiKey = "test-key",
        protocol = protocol,
    )

    private class FakeHttpConnection(
        private val responseStatus: Int,
        private val responseBody: String,
    ) : HttpURLConnection(URL("https://example.com")) {
        val requestBody = ByteArrayOutputStream()

        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = responseStatus
        override fun getInputStream(): ByteArrayInputStream =
            ByteArrayInputStream(responseBody.toByteArray(Charsets.UTF_8))
        override fun getErrorStream(): ByteArrayInputStream =
            ByteArrayInputStream(responseBody.toByteArray(Charsets.UTF_8))
        override fun getOutputStream(): ByteArrayOutputStream = requestBody
    }
}
