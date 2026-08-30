package com.luckylca.autocrack.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnthropicMessagesAdapterTest {
    @Test
    fun requestConvertsCanonicalToolHistoryAndSchema() {
        val canonical = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "system prompt"))
            .put(JSONObject().put("role", "user").put("content", "inspect phone"))
            .put(
                JSONObject()
                    .put("role", "assistant")
                    .put("content", JSONObject.NULL)
                    .put(
                        "tool_calls",
                        JSONArray().put(
                            JSONObject()
                                .put("id", "call-1")
                                .put("type", "function")
                                .put(
                                    "function",
                                    JSONObject()
                                        .put("name", "exec_bash")
                                        .put("arguments", "{\"script\":\"pwd\"}"),
                                ),
                        ),
                    ),
            )
            .put(
                JSONObject()
                    .put("role", "tool")
                    .put("tool_call_id", "call-1")
                    .put("name", "exec_bash")
                    .put("content", "{\"ok\":true}"),
            )
        val tools = listOf(
            AgentToolDefinition(
                name = "exec_bash",
                description = "run bash",
                parameters = JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject().put("script", JSONObject().put("type", "string"))),
            ),
        )
        val config = LlmProviderConfig(
            baseUrl = "https://api.minimaxi.com/anthropic/v1/messages",
            model = "MiniMax-M3",
            apiKey = "test-key",
        )

        val request = AnthropicMessagesAdapter.buildRequest(
            config = config,
            canonicalMessages = canonical,
            tools = AnthropicMessagesAdapter.buildTools(tools),
            maxTokens = 4096,
            stream = true,
        )

        assertEquals("system prompt", request.getString("system"))
        assertEquals("MiniMax-M3", request.getString("model"))
        val messages = request.getJSONArray("messages")
        assertEquals(3, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
        assertEquals("assistant", messages.getJSONObject(1).getString("role"))
        val toolUse = messages.getJSONObject(1).getJSONArray("content").getJSONObject(0)
        assertEquals("tool_use", toolUse.getString("type"))
        assertEquals("pwd", toolUse.getJSONObject("input").getString("script"))
        val toolResult = messages.getJSONObject(2).getJSONArray("content").getJSONObject(0)
        assertEquals("tool_result", toolResult.getString("type"))
        assertEquals("call-1", toolResult.getString("tool_use_id"))
        val schema = request.getJSONArray("tools").getJSONObject(0)
        assertTrue(schema.has("input_schema"))
        assertFalse(schema.has("function"))
    }

    @Test
    fun responseConvertsAnthropicToolUseToCanonicalCall() {
        val response = JSONObject()
            .put("type", "message")
            .put("stop_reason", "tool_use")
            .put(
                "content",
                JSONArray()
                    .put(JSONObject().put("type", "text").put("text", "I will inspect."))
                    .put(
                        JSONObject()
                            .put("type", "tool_use")
                            .put("id", "toolu-1")
                            .put("name", "exec_bash")
                            .put("input", JSONObject().put("script", "pwd")),
                    ),
            )

        val parsed = AnthropicMessagesAdapter.parseResponse(response)
        assertEquals("tool_use", parsed.stopReason)
        assertEquals("I will inspect.", parsed.message.getString("content"))
        val call = parsed.message.getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("toolu-1", call.getString("id"))
        assertEquals("exec_bash", call.getJSONObject("function").getString("name"))
        assertEquals("pwd", JSONObject(call.getJSONObject("function").getString("arguments")).getString("script"))
    }

    @Test
    fun streamAccumulatorHandlesTextAndPartialToolJson() {
        val stream = AnthropicMessagesAdapter.StreamAccumulator()
        stream.consume(
            JSONObject()
                .put("type", "content_block_start")
                .put("index", 0)
                .put("content_block", JSONObject().put("type", "text").put("text", "checking ")),
        )
        stream.consume(
            JSONObject()
                .put("type", "content_block_delta")
                .put("index", 0)
                .put("delta", JSONObject().put("type", "text_delta").put("text", "now")),
        )
        stream.consume(
            JSONObject()
                .put("type", "content_block_start")
                .put("index", 1)
                .put(
                    "content_block",
                    JSONObject()
                        .put("type", "tool_use")
                        .put("id", "toolu-2")
                        .put("name", "exec_bash")
                        .put("input", JSONObject()),
                ),
        )
        stream.consume(
            JSONObject()
                .put("type", "content_block_delta")
                .put("index", 1)
                .put("delta", JSONObject().put("type", "input_json_delta").put("partial_json", "{\"script\":")),
        )
        stream.consume(
            JSONObject()
                .put("type", "content_block_delta")
                .put("index", 1)
                .put("delta", JSONObject().put("type", "input_json_delta").put("partial_json", "\"pwd\"}")),
        )

        val message = stream.toCanonicalMessage()
        assertEquals("checking now", message.getString("content"))
        val call = message.getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("pwd", JSONObject(call.getJSONObject("function").getString("arguments")).getString("script"))
    }
}
