package com.luckylca.autocrack.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LlmEndpointNormalizerTest {
    @Test
    fun normalize_appendsChatCompletionsToV1Base() {
        assertEquals(
            "https://example.com/v1/chat/completions",
            LlmEndpointNormalizer.normalize("https://example.com/v1/"),
        )
    }

    @Test
    fun normalize_appendsV1ForHostBase() {
        assertEquals(
            "https://example.com/v1/chat/completions",
            LlmEndpointNormalizer.normalize("https://example.com"),
        )
    }

    @Test
    fun normalize_keepsFullEndpoint() {
        assertEquals(
            "https://example.com/openai/v1/chat/completions",
            LlmEndpointNormalizer.normalize("https://example.com/openai/v1/chat/completions"),
        )
    }

    @Test
    fun normalize_allowsPinnedCleartextProviderOnly() {
        assertEquals(
            "http://128.241.229.70:8080/v1/chat/completions",
            LlmEndpointNormalizer.normalize("http://128.241.229.70:8080/"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            LlmEndpointNormalizer.normalize("http://example.com/v1")
        }
    }

    @Test
    fun normalize_rejectsEmbeddedCredentials() {
        assertThrows(IllegalArgumentException::class.java) {
            LlmEndpointNormalizer.normalize("https://user:pass@example.com/v1")
        }
    }
}
