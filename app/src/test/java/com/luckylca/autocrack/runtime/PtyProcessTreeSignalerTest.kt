package com.luckylca.autocrack.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PtyProcessTreeSignalerTest {
    @Test
    fun plansLeafProcessGroupsBeforeParents() {
        val processes = listOf(
            process(pid = 18828, parentPid = 31471, processGroupId = 18828, name = "script"),
            process(pid = 18859, parentPid = 18828, processGroupId = 18859, name = "bash"),
            process(pid = 24330, parentPid = 18859, processGroupId = 24330, name = "sleep"),
        )

        val plan = PtyProcessSignalPlanner.build(processes, rootPid = 18828)

        assertEquals(listOf(24330, 18859, 18828), plan.processGroupIds)
    }

    @Test
    fun deduplicatesSharedJobControlGroups() {
        val processes = listOf(
            process(pid = 100, parentPid = 1, processGroupId = 100, name = "script"),
            process(pid = 101, parentPid = 100, processGroupId = 101, name = "bash"),
            process(pid = 102, parentPid = 101, processGroupId = 200, name = "worker-a"),
            process(pid = 103, parentPid = 101, processGroupId = 200, name = "worker-b"),
        )

        val plan = PtyProcessSignalPlanner.build(processes, rootPid = 100)

        assertEquals(listOf(200, 101, 100), plan.processGroupIds)
    }

    @Test
    fun fallsBackToNativeRootGroupWhenSnapshotIsEmpty() {
        val plan = PtyProcessSignalPlanner.build(emptyList(), rootPid = 18828)

        assertEquals(listOf(18828), plan.processGroupIds)
    }

    @Test
    fun buildsRootDiscardSignalRequest() {
        val request = PtyProcessSignalScriptBuilder.buildRequest(
            rootPid = 18828,
            signal = 15,
            processGroupIds = listOf(24330, 18859, 18828),
            workingDirectory = "/data/user/0/example/files/runtime",
        )

        assertEquals(HostExecutionIdentity.ROOT, request.identity)
        assertEquals(ShellOutputMode.DISCARD, request.outputMode)
        assertEquals(5_000L, request.timeoutMillis)
        assertTrue(request.command.contains("for PGID in 24330 18859 18828"))
        assertTrue(request.command.contains("kill -\"${'$'}SIGNAL\" -- \"-${'$'}PGID\""))
        assertTrue(request.command.contains("/proc/${'$'}ROOT_PID/stat"))
        assertFalse(request.command.contains("kill -18828"))
    }

    private fun process(
        pid: Int,
        parentPid: Int,
        processGroupId: Int,
        name: String,
    ): PtyProcessInfo = PtyProcessInfo(
        pid = pid,
        parentPid = parentPid,
        processGroupId = processGroupId,
        sessionId = processGroupId,
        state = "S (sleeping)",
        name = name,
        commandLine = name,
    )
}
