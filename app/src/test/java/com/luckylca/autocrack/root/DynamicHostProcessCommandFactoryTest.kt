package com.luckylca.autocrack.root

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicHostProcessCommandFactoryTest {
    @Test
    fun filteredBuildUsesProcfsAsAuthorityAndRemainsReadOnly() {
        val command = DynamicHostProcessCommandFactory.build(
            suPath = "/system/bin/su",
            filter = "com.example'app",
            maxCount = 25,
        )

        val shell = command.last()
        assertTrue(shell.contains("filter='com.example'\"'\"'app'"))
        assertTrue(shell.contains("grep -l -F -- \"\$filter\" /proc/[0-9]*/cmdline"))
        assertTrue(shell.contains("grep -l -F -- \"\$filter\" /proc/[0-9]*/comm"))
        assertTrue(shell.contains("pidof \"\$filter\""))
        assertTrue(shell.contains("emit_pid \"\$pid\""))
        assertTrue(shell.contains("[ \"\$count\" -ge \"\$max_count\" ] && break"))
        assertFalse(shell.contains("ps -A -n -ww -o PID,PPID,UID,STAT,NAME,ARGS"))
        assertReadOnly(shell)
    }

    @Test
    fun filteredProcfsDiscoveryFindsFullArgvName() {
        val marker = "com.luckylca.autocrack.testproc"
        val target = ProcessBuilder(
            "/bin/bash",
            "-c",
            "exec -a '$marker' sleep 20",
        ).start()
        try {
            Thread.sleep(100)
            val shell = DynamicHostProcessCommandFactory.build(
                suPath = "/bin/sh",
                filter = marker,
                maxCount = 25,
            ).last()
            val result = ProcessBuilder("/bin/sh", "-c", shell)
                .redirectErrorStream(true)
                .start()
            assertTrue(result.waitFor(5, TimeUnit.SECONDS))
            val output = result.inputStream.bufferedReader().readText()
            assertTrue("Expected PID ${target.pid()} in output:\n$output", output.contains("${target.pid()}\t"))
            assertTrue("Expected marker in output:\n$output", output.contains(marker))
        } finally {
            target.destroyForcibly()
            target.waitFor(2, TimeUnit.SECONDS)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun buildRejectsControlCharactersInFilter() {
        DynamicHostProcessCommandFactory.build(
            suPath = "/system/bin/su",
            filter = "com.example\nmalicious",
            maxCount = 25,
        )
    }

    private fun assertReadOnly(shell: String) {
        val normalized = shell.lowercase()
        assertFalse(normalized.contains("ptrace("))
        assertFalse(normalized.contains("gdbserver"))
        assertFalse(normalized.contains("lldb-server"))
        assertFalse(normalized.contains("kill "))
        assertFalse(normalized.contains("/proc/") && normalized.contains(" > /proc/"))
    }
}
