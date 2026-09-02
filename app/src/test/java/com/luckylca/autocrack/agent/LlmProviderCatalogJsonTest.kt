package com.luckylca.autocrack.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class LlmProviderCatalogJsonTest {
    @Test
    fun decode_migratesLegacySingleProviderAndInfersProtocol() {
        val legacy = JSONObject()
            .put("baseUrl", "https://example.com/anthropic/v1/messages")
            .put("model", "claude-test")
            .put("apiKey", "legacy-key")

        val catalog = LlmProviderCatalogJson.decode(legacy).validated()

        assertEquals(1, catalog.providers.size)
        assertEquals("migrated-default", catalog.activeProviderId)
        assertEquals("原有供应商", catalog.activeProvider?.name)
        assertEquals(LlmApiProtocol.ANTHROPIC_MESSAGES, catalog.activeProvider?.protocol)
        assertEquals("legacy-key", catalog.activeProvider?.apiKey)
    }

    @Test
    fun encodeAndDecode_preservesMultipleProvidersAndActiveSelection() {
        val first = provider("openai", "OpenAI", LlmApiProtocol.OPENAI_CHAT)
        val second = provider("anthropic", "Anthropic", LlmApiProtocol.ANTHROPIC_MESSAGES)
        val original = LlmProviderCatalog(listOf(first, second), activeProviderId = second.id).validated()

        val decoded = LlmProviderCatalogJson.decode(LlmProviderCatalogJson.encode(original)).validated()

        assertEquals(listOf("openai", "anthropic"), decoded.providers.map(LlmProviderConfig::id))
        assertEquals("anthropic", decoded.activeProviderId)
        assertEquals(LlmApiProtocol.ANTHROPIC_MESSAGES, decoded.activeProvider?.protocol)
        assertEquals("key-anthropic", decoded.activeProvider?.apiKey)
    }

    private fun provider(id: String, name: String, protocol: LlmApiProtocol): LlmProviderConfig =
        LlmProviderConfig(
            id = id,
            name = name,
            baseUrl = "https://example.com/$id/v1",
            model = "model-$id",
            apiKey = "key-$id",
            protocol = protocol,
        )
}
