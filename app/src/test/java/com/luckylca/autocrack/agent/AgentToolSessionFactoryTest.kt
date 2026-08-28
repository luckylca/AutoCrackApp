package com.luckylca.autocrack.agent

import com.luckylca.autocrack.runtime.HostProcessSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentToolSessionFactoryTest {
    @Test
    fun exactMainProcessWinsOverColonSubprocess() {
        val processes = listOf(
            process(100, "com.example.app", "com.example.app"),
            process(101, "com.example.app:worker", "com.example.app:worker"),
        )
        assertEquals(100, AgentToolSessionFactory.selectExactTargetPid("com.example.app", processes))
    }

    @Test
    fun ambiguousExactMainProcessesDisableDynamicTools() {
        val processes = listOf(
            process(100, "com.example.app", "com.example.app"),
            process(102, "com.example.app", "com.example.app --unexpected-duplicate"),
        )
        assertNull(AgentToolSessionFactory.selectExactTargetPid("com.example.app", processes))
    }

    @Test
    fun onlySubprocessDoesNotAuthorizeDynamicTools() {
        val processes = listOf(process(101, "com.example.app:worker", "com.example.app:worker"))
        assertNull(AgentToolSessionFactory.selectExactTargetPid("com.example.app", processes))
    }

    private fun process(pid: Int, name: String, commandLine: String) = HostProcessSummary(
        pid = pid,
        parentPid = 1,
        uid = 10_000,
        state = "S",
        name = name,
        commandLine = commandLine,
    )
}
