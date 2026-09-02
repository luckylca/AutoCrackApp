package com.luckylca.autocrack.ui

import com.luckylca.autocrack.agent.MobileAgentTaskSnapshot
import com.luckylca.autocrack.agent.MobileAgentTaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileAgentPictureInPictureTest {
    @Test
    fun `selects the most recently updated running task`() {
        val older = task("older", "old output", updatedAt = 10)
        val newer = task("newer", "new output", updatedAt = 20)

        val state = nextPictureInPictureState(mapOf(older.conversationId to older, newer.conversationId to newer), null)

        assertEquals("newer", state?.conversationId)
        assertEquals("new output", state?.output)
        assertTrue(state?.isRunning == true)
    }

    @Test
    fun `keeps the last model output while tools run and after completion`() {
        val previous = MobileAgentPictureInPictureState("conversation", "模型正在回复", "answer so far", true)
        val runningTool = task("conversation", "", updatedAt = 20, stage = "正在运行 bash")
        val duringTool = nextPictureInPictureState(mapOf("conversation" to runningTool), previous)
        val completed = runningTool.copy(status = MobileAgentTaskStatus.COMPLETED, stage = "已完成")
        val finished = nextPictureInPictureState(mapOf("conversation" to completed), duringTool)

        assertEquals("answer so far", duringTool?.output)
        assertEquals("answer so far", finished?.output)
        assertFalse(finished?.isRunning ?: true)
        assertEquals("已完成", finished?.stage)
    }

    @Test
    fun `creates a compact tail without markdown control characters`() {
        val excerpt = pictureInPictureOutputExcerpt("# Result\n\n- **first**\n- `second`", maxChars = 700)

        assertEquals("Result\n\nfirst\nsecond", excerpt)
    }

    private fun task(
        id: String,
        output: String,
        updatedAt: Long,
        stage: String = "模型正在回复",
    ) = MobileAgentTaskSnapshot(
        conversationId = id,
        status = MobileAgentTaskStatus.RUNNING,
        stage = stage,
        streamingText = output,
        startedAtEpochMillis = 1,
        updatedAtEpochMillis = updatedAt,
    )
}
