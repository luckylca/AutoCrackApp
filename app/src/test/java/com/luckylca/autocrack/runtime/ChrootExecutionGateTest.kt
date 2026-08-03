package com.luckylca.autocrack.runtime

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChrootExecutionGateTest {
    @Before
    fun setUp() {
        ChrootExecutionGate.resetForTest()
    }

    @After
    fun tearDown() {
        ChrootExecutionGate.resetForTest()
    }

    @Test
    fun persistentPtyBlocksOneShotExecution() {
        ChrootExecutionGate.acquire(ChrootExecutionKind.PERSISTENT_PTY)

        val failure = assertThrows(IllegalStateException::class.java) {
            ChrootExecutionGate.acquire(ChrootExecutionKind.ONE_SHOT)
        }

        assertEquals(ChrootExecutionKind.PERSISTENT_PTY, ChrootExecutionGate.current())
        assertTrue(failure.message.orEmpty().contains("持久 PTY"))
    }

    @Test
    fun oneShotExecutionBlocksPtyStartup() {
        ChrootExecutionGate.acquire(ChrootExecutionKind.ONE_SHOT)

        val failure = assertThrows(IllegalStateException::class.java) {
            ChrootExecutionGate.acquire(ChrootExecutionKind.PERSISTENT_PTY)
        }

        assertEquals(ChrootExecutionKind.ONE_SHOT, ChrootExecutionGate.current())
        assertTrue(failure.message.orEmpty().contains("一次性 Debian 命令"))
    }

    @Test
    fun releaseAllowsTheNextExecutionMode() {
        ChrootExecutionGate.acquire(ChrootExecutionKind.PERSISTENT_PTY)
        ChrootExecutionGate.release(ChrootExecutionKind.PERSISTENT_PTY)
        assertNull(ChrootExecutionGate.current())

        ChrootExecutionGate.acquire(ChrootExecutionKind.ONE_SHOT)
        assertEquals(ChrootExecutionKind.ONE_SHOT, ChrootExecutionGate.current())
    }

    @Test
    fun cleanupRequiresAnIdleGate() {
        ChrootExecutionGate.acquire(ChrootExecutionKind.PERSISTENT_PTY)

        assertThrows(IllegalStateException::class.java) {
            ChrootExecutionGate.requireIdle()
        }
    }
}
