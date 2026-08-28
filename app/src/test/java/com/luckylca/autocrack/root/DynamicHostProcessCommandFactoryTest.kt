package com.luckylca.autocrack.root

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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
        assertTrue(shell.contains("grep -l -F -- \"${'$'}filter\" /proc/[0-9]*/cmdline"))
        assertTrue(shell.contains("grep -l -F -- \"${'$'}filter\" /proc/[0-9]*/comm"))
        assertTrue(shell.contains("pidof \"${'$'}filter\""))
        assertTrue(shell.contains("matches_filter \"${'$'}parent_pid\""))
        assertTrue(shell.contains("parent_candidate=\"${'$'}parent_pid\""))
        assertTrue(shell.contains("parent_matched=0"))
        assertTrue(shell.contains("parent_matched=1"))
        assertTrue(shell.contains("AUTOCRACK_DISCOVERY"))
        assertTrue(shell.contains("emit_pid \"${'$'}pid\""))
        assertTrue(shell.contains("[ \"${'$'}count\" -ge \"${'$'}max_count\" ] && break"))
        assertFalse(shell.contains("[ \"${'$'}pid\" = \"${'$'}parent_pid\" ]"))
        assertFalse(shell.contains("ps -A -n -ww -o PID,PPID,UID,STAT,NAME,ARGS"))
        assertReadOnly(shell)
    }

    @Test
    fun filteredProcfsDiscoveryFindsFullArgvName() {
        assumeTrue("Host /proc is required for this procfs integration test", File("/proc").isDirectory)
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
            val processRows = output.lineSequence()
                .filter { line -> line.isNotBlank() && !line.startsWith("pid\t") }
                .filterNot { line -> line.startsWith("AUTOCRACK_DISCOVERY") }
                .toList()
            assertTrue("Expected at least one process row:\n$output", processRows.isNotEmpty())
            assertTrue("Expected marker in output:\n$output", output.contains(marker))
        } finally {
            target.destroyForcibly()
            target.waitFor(2, TimeUnit.SECONDS)
        }
    }

    @Test
    fun filteredDiscoveryKeepsMatchingParentProcess() {
        assumeTrue("Host /proc is required for this procfs integration test", File("/proc").isDirectory)
        val marker = "com.luckylca.autocrack.parentproc"
        val shell = DynamicHostProcessCommandFactory.build(
            suPath = "/bin/sh",
            filter = marker,
            maxCount = 25,
        ).last()
        val childScript = File.createTempFile("autocrack-parent-discovery-child-", ".sh")
        val parentScript = File.createTempFile("autocrack-parent-discovery-parent-", ".sh")
        childScript.writeText(shell)
        parentScript.writeText(
            """
            #!/bin/bash
            /bin/sh "${'$'}1"
            sleep 1
            """.trimIndent() + "\n",
        )

        try {
            // Keep the marker process alive while the generated command is executing. A one-command
            // `bash -c` may exec its child as an optimization, which destroys the parent relationship
            // this regression is specifically intended to model.
            val result = ProcessBuilder(
                "/bin/bash",
                "-c",
                "exec -a '$marker' /bin/bash ${shellQuote(parentScript.absolutePath)} " +
                    shellQuote(childScript.absolutePath),
            )
                .redirectErrorStream(true)
                .start()
            assertTrue(result.waitFor(5, TimeUnit.SECONDS))
            val output = result.inputStream.bufferedReader().readText()
            assertTrue("Expected matching parent process row:\n$output", output.contains(marker))
            assertTrue("Expected parent match diagnostic:\n$output", output.contains("parent_matched=1"))
            assertTrue("Expected emitted process:\n$output", output.contains("emitted=1"))
        } finally {
            childScript.delete()
            parentScript.delete()
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

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"
}
