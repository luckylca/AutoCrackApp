package com.luckylca.autocrack.root

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicHostProcessCommandFactoryTest {
    @Test
    fun buildUsesSinglePsSnapshotBeforeBoundedProcFallback() {
        val command = DynamicHostProcessCommandFactory.build(
            suPath = "/system/bin/su",
            filter = "com.example'app",
            maxCount = 25,
        )

        val shell = command.last()
        assertTrue(shell.contains("filter='com.example'\"'\"'app'"))
        assertTrue(shell.contains("ps -A -n -ww -o PID,PPID,UID,STAT,NAME,ARGS"))
        assertTrue(shell.contains("for proc in /proc/[0-9]*"))
        assertTrue(shell.contains("pid == ENVIRON[\"self_pid\"]"))
        assertTrue(shell.contains("pid == ENVIRON[\"parent_pid\"]"))
        assertTrue(shell.contains("count >= (ENVIRON[\"max_count\"] + 0)"))
        assertFalse(shell.contains("ptrace("))
        assertFalse(shell.contains("gdbserver"))
        assertFalse(shell.contains("lldb-server"))
        assertFalse(shell.contains("kill "))
        assertFalse(shell.contains("/proc/") && shell.contains(" > /proc/"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun buildRejectsControlCharactersInFilter() {
        DynamicHostProcessCommandFactory.build(
            suPath = "/system/bin/su",
            filter = "com.example\nmalicious",
            maxCount = 25,
        )
    }
}
