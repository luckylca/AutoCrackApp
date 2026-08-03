package com.luckylca.autocrack.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PtyProcessSupervisorTest {
    @Test
    fun buildsFileBackedGlobalProcSnapshot() {
        val snapshotPath = "/data/user/0/example/files/runtime/tmp/proc snapshot.txt"
        val script = PtyProcessProbeScriptBuilder.build(30891, snapshotPath)

        assertTrue(script.contains("ROOT_PID=30891"))
        assertTrue(script.contains("SNAPSHOT_FILE='${snapshotPath}'"))
        assertTrue(script.contains("/proc/[0-9]*"))
        assertTrue(script.contains("PROCESS_TABLE_BEGIN"))
        assertTrue(script.contains("PROCESS_TABLE_END"))
        assertTrue(script.contains("> \"${'$'}SNAPSHOT_FILE\""))
        assertFalse(script.contains("walk_process"))
        assertFalse(script.contains("CHILDREN_FILE="))
    }

    @Test
    fun fileBackedProbeDiscardsShellPipes() {
        val request = PtyProcessProbeScriptBuilder.buildRequest(
            rootPid = 30891,
            snapshotPath = "/data/user/0/example/files/runtime/tmp/proc.snapshot",
            workingDirectory = "/data/user/0/example/files/runtime",
        )

        assertEquals(ShellOutputMode.DISCARD, request.outputMode)
        assertEquals(HostExecutionIdentity.ROOT, request.identity)
        assertEquals(10_000L, request.timeoutMillis)
    }

    @Test
    fun rejectsIncompleteProcessTable() {
        val output = """
            PROCESS_TABLE_BEGIN
            R|42|42 (bash) S 1 42 42 0 -1|bash
        """.trimIndent()

        assertFalse(PtyProcessProbeParser.hasCompleteTable(output))
        assertTrue(PtyProcessProbeParser.parse(output, rootPid = 42).isEmpty())
    }

    @Test
    fun parsesStatRecordsAndReturnsOnlyRootDescendants() {
        val output = """
            unrelated output
            PROCESS_TABLE_BEGIN
            R|41000|41000 (unrelated) S 1 41000 41000 0 -1|/system/bin/unrelated
            R|30911|30911 (sleep) S 30910 30910 30910 0 -1|sleep 60
            R|30891|30891 (script) S 1 30891 30891 0 -1|/usr/bin/script -q
            R|30910|30910 (bash) S 30891 30910 30910 0 -1|/bin/bash --noprofile --norc -i
            malformed
            PROCESS_TABLE_END
            R|999|999 (ignored) R 1 999 999 0 -1|ignored
        """.trimIndent()

        assertTrue(PtyProcessProbeParser.hasCompleteTable(output))
        val processes = PtyProcessProbeParser.parse(output, rootPid = 30891)

        assertEquals(listOf(30891, 30910, 30911), processes.map(PtyProcessInfo::pid))
        assertEquals(30910, processes[1].processGroupId)
        assertEquals(30910, processes[1].sessionId)
        assertEquals("bash", processes[1].name)
        assertEquals("/bin/bash --noprofile --norc -i", processes[1].commandLine)
        assertEquals("S (sleeping)", processes[2].state)
    }

    @Test
    fun parsesCommandNamesContainingClosingParenthesis() {
        val output = """
            PROCESS_TABLE_BEGIN
            R|42|42 (worker) name)) S 1 42 42 0 -1|worker
            PROCESS_TABLE_END
        """.trimIndent()

        val processes = PtyProcessProbeParser.parse(output, rootPid = 42)

        assertEquals(1, processes.size)
        assertEquals("worker) name)", processes.single().name)
        assertEquals(42, processes.single().processGroupId)
    }

    @Test
    fun deduplicatesRepeatedProcessTableRecords() {
        val output = """
            PROCESS_TABLE_BEGIN
            R|42|42 (bash) S 1 42 42 0 -1|bash
            R|42|42 (bash) S 1 42 42 0 -1|bash
            PROCESS_TABLE_END
        """.trimIndent()

        assertEquals(1, PtyProcessProbeParser.parse(output, rootPid = 42).size)
    }
}
