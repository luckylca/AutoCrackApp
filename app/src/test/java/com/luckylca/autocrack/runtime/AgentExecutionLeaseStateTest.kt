package com.luckylca.autocrack.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentExecutionLeaseStateTest {
    @Test
    fun overlappingLeasesRemainActiveUntilFinalRelease() {
        val state = AgentExecutionLeaseState()

        val first = state.acquire("lease-a", "conversation-a", "会话一", "正在思考")
        assertEquals(1, first.count)
        assertEquals("conversation-a", first.latestConversationId)
        assertEquals("会话一", first.latestLabel)
        assertEquals("正在思考", first.latestStage)

        val second = state.acquire("lease-b", "conversation-b", "会话二", "正在运行 bash")
        assertEquals(2, second.count)
        assertEquals("conversation-b", second.latestConversationId)
        assertEquals("会话二", second.latestLabel)
        assertEquals("正在运行 bash", second.latestStage)

        val updated = state.update("lease-b", "Agent 正在回复")
        assertEquals("会话二", updated.latestLabel)
        assertEquals("Agent 正在回复", updated.latestStage)

        val afterFirstRelease = state.release("lease-a")
        assertEquals(1, afterFirstRelease.count)
        assertEquals("会话二", afterFirstRelease.latestLabel)

        val afterFinalRelease = state.release("lease-b")
        assertEquals(0, afterFinalRelease.count)
        assertNull(afterFinalRelease.latestConversationId)
        assertNull(afterFinalRelease.latestLabel)
        assertNull(afterFinalRelease.latestStage)
    }

    @Test
    fun unknownReleaseDoesNotAffectActiveLease() {
        val state = AgentExecutionLeaseState()
        state.acquire("lease-a", "conversation-a", "目标会话", "正在工作")

        val snapshot = state.release("not-present")

        assertEquals(1, snapshot.count)
        assertEquals("目标会话", snapshot.latestLabel)
        assertEquals("正在工作", snapshot.latestStage)
    }
}
