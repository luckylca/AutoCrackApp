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
