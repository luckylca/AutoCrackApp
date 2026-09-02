package com.luckylca.autocrack.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileAgentProtocolRepairTest {
    @Test
    fun `synthesizes missing results before the next user message`() {
        val assistant = assistantCall("call-1", "exec_bash")
        val user = message("user", MobileAgentRole.USER, "继续")

        val result = MobileAgentProtocolRepair.repair(listOf(assistant, user))

        assertEquals(1, result.synthesizedToolResults)
        assertEquals(listOf(MobileAgentRole.ASSISTANT, MobileAgentRole.TOOL, MobileAgentRole.USER), result.messages.map { it.role })
        assertEquals("call-1", result.messages[1].toolCallId)
        assertTrue(JSONObject(result.messages[1].content).getBoolean("interrupted"))
    }

    @Test
    fun `preserves completed multi-call rounds`() {
        val assistant = MobileAgentMessage(
            id = "assistant",
            role = MobileAgentRole.ASSISTANT,
            content = "",
            createdAtEpochMillis = 1,
            toolCallsJson = JSONArray()
                .put(call("call-1", "exec_bash"))
                .put(call("call-2", "read_file"))
                .toString(),
        )
        val first = toolResult("call-1", "exec_bash")
        val second = toolResult("call-2", "read_file")

        val result = MobileAgentProtocolRepair.repair(listOf(assistant, first, second))

        assertEquals(0, result.synthesizedToolResults)
        assertEquals(listOf(assistant, first, second), result.messages)
    }

    @Test
    fun `drops orphan results when a compaction boundary starts mid-round`() {
        val orphan = toolResult("old-call", "exec_bash")
        val user = message("user", MobileAgentRole.USER, "next")

        val result = MobileAgentProtocolRepair.repair(listOf(orphan, user))

        assertEquals(1, result.droppedOrphanToolResults)
        assertEquals(listOf(user), result.messages)
    }

    private fun assistantCall(callId: String, name: String) = MobileAgentMessage(
        id = "assistant-$callId",
        role = MobileAgentRole.ASSISTANT,
        content = "",
        createdAtEpochMillis = 1,
        toolCallsJson = JSONArray().put(call(callId, name)).toString(),
    )

    private fun call(callId: String, name: String) = JSONObject()
        .put("id", callId)
        .put("type", "function")
        .put("function", JSONObject().put("name", name).put("arguments", "{}"))

    private fun toolResult(callId: String, name: String) = MobileAgentMessage(
        id = "result-$callId",
        role = MobileAgentRole.TOOL,
        content = "{\"ok\":true}",
        createdAtEpochMillis = 2,
        toolCallId = callId,
        toolName = name,
    )

    private fun message(id: String, role: MobileAgentRole, content: String) = MobileAgentMessage(
        id = id,
        role = role,
        content = content,
        createdAtEpochMillis = 3,
    )
}
