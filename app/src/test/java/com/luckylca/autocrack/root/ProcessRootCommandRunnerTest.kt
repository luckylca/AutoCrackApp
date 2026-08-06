package com.luckylca.autocrack.root

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessRootCommandRunnerTest {
    @Test
    fun timeoutPreservesTimedOutResultWhenDescendantKeepsPipesOpen() = runBlocking {
        val result = ProcessRootCommandRunner().run(
            command = listOf(
                "sh",
                "-c",
                "trap '' TERM; sleep 2",
            ),
            label = "timeout pipe close regression",
            timeoutMillis = 50L,
        )

        assertTrue(result.timedOut)
        assertNull(result.exitCode)
        assertNull(result.failure)
    }
}
