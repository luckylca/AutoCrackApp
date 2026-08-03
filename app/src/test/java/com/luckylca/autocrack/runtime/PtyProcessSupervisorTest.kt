package com.luckylca.autocrack.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PtyProcessSupervisorTest {
    @Test
    fun buildsFastFileBackedStatSnapshot() {
        val snapshotPath = "/data/user/0/example/files/runtime/tmp/proc stat.txt"
        val script = PtyProcessProbeScriptBuilder.buildStatSnapshot(30891, snapshotPath)

        assertTrue(script.contains("ROOT_PID=30891"))
        assertTrue(script.contains("SNAPSHOT_FILE='${snapshotPath}'"))
        assertTrue(script.contains("/proc/[0-9]*"))
        assertTrue(script.contains("IFS= read -r STAT_LINE"))
        assertTrue(script.contains("PROCESS_TABLE_BEGIN"))
        assertTrue(script.contains("PROCESS_TABLE_END"))
        assertTrue(script.contains("> \"${'$'}SNAPSHOT_FILE\""))
        assertFalse(script.contains("cat \"${'$'}STAT_FILE\""))
        assertFalse(script.contains("/cmdline"))
        assertFalse(script.contains("CHILDREN_FILE="))
    }

    @Test
    fun statProbeDiscardsShellPipes() {
        val request = PtyProcessProbeScriptBuilder.buildStatRequest(
            rootPid = 30891,
            snapshotPath = "/data/user/0/example/files/runtime/tmp/proc.snapshot",
            workingDirectory = "/data/user/0/example/files/runtime",
        )

        assertEquals(ShellOutputMode.DISCARD, request.outputMode)
        assertEquals(HostExecutionIdentity.ROOT, request.identity)
        assertEquals(10_000L, request.timeoutMillis)
    }

    @Test
    fun buildsTargetedCommandLineSnapshot() {
        val snapshotPath = "/data/user/0/example/files/runtime/tmp/cmdline snapshot.txt"
        val script = PtyProcessProbeScriptBuilder.buildCommandLineSnapshot(
            pids = listOf(30891, 30910, 30911, 30910),
            snapshotPath = snapshotPath,
        )

        assertTrue(script.contains("for PID in 30891 30910 30911"))
        assertTrue(script.contains("/proc/${'$'}PID/cmdline"))
        assertTrue(script.contains("CMDLINE_TABLE_BEGIN"))
        assertTrue(script.contains("CMDLINE_TABLE_END"))
        assertTrue(script.contains("tr '\\000\\011\\012\\015'"))
    }

    @Test
    fun commandLineProbeAlsoDiscardsShellPipes() {
        val request = PtyProcessProbeScriptBuilder.buildCommandLineRequest(
            pids = listOf(30891, 30910),
            snapshotPath = "/data/user/0/example/files/runtime/tmp/cmdline.snapshot",
            workingDirectory = "/data/user/0/example/files/runtime",
        )

        assertEquals(ShellOutputMode.DISCARD, request.outputMode)
        assertEquals(HostExecutionIdentity.ROOT, request.identity)
        assertEquals(5_000L, request.timeoutMillis)
    }

    @Test
    fun rejectsIncompleteProcessTable() {
        val output = """
            PROCESS_TABLE_BEGIN
            R|42|42 (bash) S 1 42 42 0 -1
        """.trimIndent()

        assertFalse(PtyProcessProbeParser.hasCompleteTable(output))
        assertTrue(PtyProcessProbeParser.parse(output, rootPid = 42).isEmpty())
    }

    @Test
    fun parsesStatsSelectsDescendantsAndEnrichesCommandLines() {
        val statOutput = """
            unrelated output
            PROCESS_TABLE_BEGIN
            R|41000|41000 (unrelated) S 1 41000 41000 0 -1
            R|30911|30911 (sleep) S 30910 30910 30910 0 -1
            R|30891|30891 (script) S 1 30891 30891 0 -1
            R|30910|30910 (bash) S 30891 30910 30910 0 -1
            malformed
            PROCESS_TABLE_END
            R|999|999 (ignored) R 1 999 999 0 -1
        """.trimIndent()
        val commandLineOutput = """
            CMDLINE_TABLE_BEGIN
            C|30891|/usr/bin/script -q
            C|30910|/bin/bash --noprofile --norc -i
            C|30911|sleep 60
            CMDLINE_TABLE_END
        """.trimIndent()

        val baseProcesses = PtyProcessProbeParser.parse(statOutput, rootPid = 30891)
        val commandLines = PtyProcessProbeParser.parseCommandLines(commandLineOutput)
        val processes = PtyProcessProbeParser.enrichCommandLines(baseProcesses, commandLines)

        assertEquals(listOf(30891, 30910, 30911), processes.map(PtyProcessInfo::pid))
        assertEquals(30910, processes[1].processGroupId)
        assertEquals(30910, processes[1].sessionId)
        assertEquals("bash", processes[1].name)
        assertEquals("/bin/bash --noprofile --norc -i", processes[1].commandLine)
        assertEquals("sleep 60", processes[2].commandLine)
        assertEquals("S (sleeping)", processes[2].state)
    }

    @Test
    fun preservesPipesInsideNamesAndCommandLines() {
        val statOutput = """
            PROCESS_TABLE_BEGIN
            R|42|42 (worker|name)) S 1 42 42 0 -1
            PROCESS_TABLE_END
        """.trimIndent()
        val commandLineOutput = """
            CMDLINE_TABLE_BEGIN
            C|42|worker --value=a|b
            CMDLINE_TABLE_END
        """.trimIndent()

        val base = PtyProcessProbeParser.parse(statOutput, rootPid = 42)
        val enriched = PtyProcessProbeParser.enrichCommandLines(
            base,
            PtyProcessProbeParser.parseCommandLines(commandLineOutput),
        )

        assertEquals(1, enriched.size)
        assertEquals("worker|name)", enriched.single().name)
        assertEquals("worker --value=a|b", enriched.single().commandLine)
        assertEquals(42, enriched.single().processGroupId)
    }

    @Test
    fun fallsBackToStatNameWhenCommandLineDisappears() {
        val statOutput = """
            PROCESS_TABLE_BEGIN
            R|42|42 (bash) S 1 42 42 0 -1
            PROCESS_TABLE_END
        """.trimIndent()

        val processes = PtyProcessProbeParser.enrichCommandLines(
            PtyProcessProbeParser.parse(statOutput, rootPid = 42),
            emptyMap(),
        )

        assertEquals("[bash]", processes.single().commandLine)
    }

    @Test
    fun deduplicatesRepeatedProcessTableRecords() {
        val output = """
            PROCESS_TABLE_BEGIN
            R|42|42 (bash) S 1 42 42 0 -1
            R|42|42 (bash) S 1 42 42 0 -1
            PROCESS_TABLE_END
        """.trimIndent()

        assertEquals(1, PtyProcessProbeParser.parse(output, rootPid = 42).size)
    }
}
