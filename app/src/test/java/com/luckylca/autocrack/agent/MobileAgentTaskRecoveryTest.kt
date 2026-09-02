package com.luckylca.autocrack.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MobileAgentTaskRecoveryTest {
    @Test
    fun runningTaskBecomesResumableInterruptedTaskAfterRestart() {
        val running = snapshot(MobileAgentTaskStatus.RUNNING).copy(streamingText = "partial")
        val completed = snapshot(MobileAgentTaskStatus.COMPLETED)

        val recovered = recoverInterruptedTasks(
            mapOf("running" to running, "completed" to completed),
            nowEpochMillis = 99L,
        )

        val interrupted = requireNotNull(recovered["running"])
        assertEquals(MobileAgentTaskStatus.INTERRUPTED, interrupted.status)
        assertEquals("", interrupted.streamingText)
        assertEquals(99L, interrupted.updatedAtEpochMillis)
        assertEquals("任务执行进程已重启，可以在原会话中继续。", interrupted.error)
        assertSame(completed, recovered["completed"])
    }

    private fun snapshot(status: MobileAgentTaskStatus) = MobileAgentTaskSnapshot(
        conversationId = status.name,
        status = status,
        stage = "stage",
        streamingText = "",
        startedAtEpochMillis = 1L,
        updatedAtEpochMillis = 2L,
    )
}
