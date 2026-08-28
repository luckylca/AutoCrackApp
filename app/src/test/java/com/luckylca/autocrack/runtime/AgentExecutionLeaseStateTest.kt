package com.luckylca.autocrack.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentExecutionLeaseStateTest {
    @Test
    fun overlappingLeasesRemainActiveUntilFinalRelease() {
        val state = AgentExecutionLeaseState()

        val first = state.acquire("lease-a", "com.example.first")
        assertEquals(1, first.count)
        assertEquals("com.example.first", first.latestPackageName)

        val second = state.acquire("lease-b", "com.example.second")
        assertEquals(2, second.count)
        assertEquals("com.example.second", second.latestPackageName)

        val afterFirstRelease = state.release("lease-a")
        assertEquals(1, afterFirstRelease.count)
        assertEquals("com.example.second", afterFirstRelease.latestPackageName)

        val afterFinalRelease = state.release("lease-b")
        assertEquals(0, afterFinalRelease.count)
        assertNull(afterFinalRelease.latestPackageName)
    }

    @Test
    fun unknownReleaseDoesNotAffectActiveLease() {
        val state = AgentExecutionLeaseState()
        state.acquire("lease-a", "com.example.target")

        val snapshot = state.release("not-present")

        assertEquals(1, snapshot.count)
        assertEquals("com.example.target", snapshot.latestPackageName)
    }
}
