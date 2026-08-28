package com.luckylca.autocrack.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPerfettoToolExecutorTest {
    @Test
    fun fixedSqlIsReadOnlyAndBoundToPackage() {
        val sql = AgentPerfettoToolExecutor.fixedTargetSql("com.example.myapplication")
        assertTrue(sql.contains("p.name = 'com.example.myapplication'"))
        assertTrue(sql.contains("LIMIT 16"))
        assertFalse(sql.contains("DELETE", ignoreCase = true))
        assertFalse(sql.contains("UPDATE", ignoreCase = true))
        assertFalse(sql.contains("INSERT", ignoreCase = true))
    }

    @Test
    fun fixedSqlRejectsInvalidPackageName() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentPerfettoToolExecutor.fixedTargetSql("bad'; DROP TABLE sched; --")
        }
    }
}
