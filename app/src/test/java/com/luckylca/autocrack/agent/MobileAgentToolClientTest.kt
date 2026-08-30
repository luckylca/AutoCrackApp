package com.luckylca.autocrack.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileAgentToolClientTest {
    @Test
    fun protocolPreservesToolMessagesAfterSummaryBoundary() {
        val toolCalls = JSONArray()
            .put(
                JSONObject()
                    .put("id", "call-1")
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", "exec_bash")
                            .put("arguments", "{\"script\":\"pwd\"}"),
                    ),
            )
            .toString()
        val conversation = MobileAgentConversation(
            id = "session-1",
            title = "test",
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 8,
            summary = "Earlier work already found the project root.",
            summaryThroughMessageId = "old-assistant",
            messages = listOf(
                message("old-user", MobileAgentRole.USER, "old question", 1),
                message("old-assistant", MobileAgentRole.ASSISTANT, "old answer", 2),
                MobileAgentMessage(
                    id = "recent-user",
                    role = MobileAgentRole.USER,
                    content = "inspect this file",
                    createdAtEpochMillis = 3,
                    attachments = listOf(
                        MobileAgentAttachment(
                            id = "attachment-1",
                            displayName = "sample.apk",
                            relativePath = "attachments/attachment-1-sample.apk",
                            mimeType = "application/vnd.android.package-archive",
                            sizeBytes = 1234,
                        ),
                    ),
                ),
                MobileAgentMessage(
                    id = "assistant-call",
                    role = MobileAgentRole.ASSISTANT,
                    content = "",
                    createdAtEpochMillis = 4,
                    toolCallsJson = toolCalls,
                ),
                MobileAgentMessage(
                    id = "tool-result",
                    role = MobileAgentRole.TOOL,
                    content = "{\"ok\":true}",
                    createdAtEpochMillis = 5,
                    toolCallId = "call-1",
                    toolName = "exec_bash",
                ),
                message("final", MobileAgentRole.ASSISTANT, "done", 6),
            ),
        )

        val messages = MobileAgentToolClient().buildProtocolMessagesForTesting("system", conversation)

        assertEquals(6, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("system", messages.getJSONObject(1).getString("role"))
        assertTrue(messages.getJSONObject(1).getString("content").contains("Earlier work"))
        assertEquals("user", messages.getJSONObject(2).getString("role"))
        assertTrue(messages.getJSONObject(2).getString("content").contains("/workspace/attachments/attachment-1-sample.apk"))
        assertFalse(messages.getJSONObject(2).getString("content").contains("old question"))
        assertEquals("assistant", messages.getJSONObject(3).getString("role"))
        assertEquals("call-1", messages.getJSONObject(3).getJSONArray("tool_calls").getJSONObject(0).getString("id"))
        assertEquals("tool", messages.getJSONObject(4).getString("role"))
        assertEquals("call-1", messages.getJSONObject(4).getString("tool_call_id"))
        assertEquals("assistant", messages.getJSONObject(5).getString("role"))
        assertEquals("done", messages.getJSONObject(5).getString("content"))
    }

    @Test
    fun protocolCapsLargeToolResultsSentBackToModel() {
        val largeResult = "x".repeat(50_000)
        val conversation = MobileAgentConversation(
            id = "session-large-tool",
            title = "test",
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 2,
            messages = listOf(
                message("user", MobileAgentRole.USER, "inspect", 1),
                MobileAgentMessage(
                    id = "tool-result",
                    role = MobileAgentRole.TOOL,
                    content = largeResult,
                    createdAtEpochMillis = 2,
                    toolCallId = "call-large",
                    toolName = "exec_bash",
                ),
            ),
        )

        val messages = MobileAgentToolClient().buildProtocolMessagesForTesting("system", conversation)
        val modelToolContent = messages.getJSONObject(2).getString("content")

        assertEquals(16_000, modelToolContent.length)
        assertEquals(50_000, conversation.messages.last().content.length)
    }

    @Test
    fun loopCompactionKeepsUserAnchorAndCompleteToolCallGroup() {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "system"))
            .put(JSONObject().put("role", "user").put("content", "original task"))
        repeat(8) { index ->
            val callId = "old-call-$index"
            messages
                .put(assistantToolCall(callId))
                .put(toolResult(callId))
        }
        val retainedCallIds = listOf("recent-call-1", "recent-call-2")
        messages.put(
            JSONObject()
                .put("role", "assistant")
                .put("content", JSONObject.NULL)
                .put(
                    "tool_calls",
                    JSONArray().apply { retainedCallIds.forEach { put(toolCall(it)) } },
                ),
        )
        retainedCallIds.forEach { messages.put(toolResult(it)) }

        val compacted = MobileAgentToolClient()
            .buildCompactedLoopMessagesForTesting(messages, "verified state")

        assertEquals("system", compacted.getJSONObject(0).getString("role"))
        assertEquals("user", compacted.getJSONObject(1).getString("role"))
        assertTrue(compacted.getJSONObject(1).getString("content").contains("verified state"))
        val retainedAssistant = compacted.getJSONObject(compacted.length() - 3)
        assertEquals("assistant", retainedAssistant.getString("role"))
        assertEquals(
            retainedCallIds,
            retainedAssistant.getJSONArray("tool_calls").let { calls ->
                List(calls.length()) { calls.getJSONObject(it).getString("id") }
            },
        )
        assertEquals("recent-call-1", compacted.getJSONObject(compacted.length() - 2).getString("tool_call_id"))
        assertEquals("recent-call-2", compacted.getJSONObject(compacted.length() - 1).getString("tool_call_id"))
    }

    @Test
    fun brokenPersistedSummaryDoesNotHideOriginalMessages() {
        val conversation = MobileAgentConversation(
            id = "broken-summary",
            title = "test",
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 2,
            summary = "# 长",
            summaryThroughMessageId = "old-assistant",
            messages = listOf(
                message("old-user", MobileAgentRole.USER, "find OfflineFeedParam", 1),
                message("old-assistant", MobileAgentRole.ASSISTANT, "found FeedCacheConfigItem.maxSize", 2),
            ),
        )

        val messages = MobileAgentToolClient().buildProtocolMessagesForTesting("system", conversation)

        assertEquals(3, messages.length())
        assertTrue(messages.getJSONObject(1).getString("content").contains("OfflineFeedParam"))
        assertTrue(messages.getJSONObject(2).getString("content").contains("FeedCacheConfigItem.maxSize"))
    }

    @Test
    fun generatedCheckpointRejectsTruncatedPlaceholder() {
        assertFalse(MobileAgentCompactionPolicy.isUsablePersistedSummary("# 长"))
        assertTrue(
            MobileAgentCompactionPolicy.generatedSummaryError("# 长", "OfflineFeedParam") != null,
        )
    }

    @Test
    fun sourceContextKeepsLatestCorrectionsAndStableIdentifiers() {
        val messages = buildList {
            add(message("goal", MobileAgentRole.USER, "修改缓存视频个数", 1))
            repeat(80) { index ->
                add(message("tool-$index", MobileAgentRole.TOOL, "noise-$index ${"x".repeat(300)}", index + 2L))
            }
            add(message("finding", MobileAgentRole.ASSISTANT, "找到 OfflineFeedParam 和 FeedCacheConfigItem.maxSize", 100))
            add(message("correction", MobileAgentRole.USER, "不要重新搜索，沿 C22590py/C22600pz 的引用继续", 101))
        }

        val context = MobileAgentCompactionPolicy.buildSourceContext(messages, 8_000)

        assertTrue(context.contains("不要重新搜索"))
        assertTrue(context.contains("OfflineFeedParam"))
        assertTrue(context.contains("FeedCacheConfigItem.maxSize"))
        assertTrue(context.contains("C22590py/C22600pz"))
    }

    private fun assistantToolCall(callId: String): JSONObject = JSONObject()
        .put("role", "assistant")
        .put("content", JSONObject.NULL)
        .put("tool_calls", JSONArray().put(toolCall(callId)))

    private fun toolCall(callId: String): JSONObject = JSONObject()
        .put("id", callId)
        .put("type", "function")
        .put(
            "function",
            JSONObject()
                .put("name", "exec_bash")
                .put("arguments", "{\"script\":\"pwd\"}"),
        )

    private fun toolResult(callId: String): JSONObject = JSONObject()
        .put("role", "tool")
        .put("tool_call_id", callId)
        .put("content", "{\"ok\":true}")

    private fun message(
        id: String,
        role: MobileAgentRole,
        content: String,
        timestamp: Long,
    ): MobileAgentMessage = MobileAgentMessage(
        id = id,
        role = role,
        content = content,
        createdAtEpochMillis = timestamp,
    )
}
