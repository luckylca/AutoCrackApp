package com.luckylca.autocrack.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PtyProcessSupervisorTest {
    @Test
    fun buildsSingleDebianProcpsSnapshot() {
        val snapshotPath = "/data/user/0/example/files/runtime/tmp/process table.txt"
        val rootfsPath = "/data/user/0/example/files/runtime/rootfs/current"
        val script = PtyProcessProbeScriptBuilder.buildProcessTableSnapshot(
            rootPid = 30891,
            snapshotPath = snapshotPath,
            rootfsPath = rootfsPath,
            snapshotOwnerUid = 10_321,
            snapshotOwnerGid = 10_321,
        )

        assertTrue(script.contains("ROOT_PID=30891"))
        assertTrue(script.contains("ROOTFS='${rootfsPath}'"))
        assertTrue(script.contains("SNAPSHOT_FILE='${snapshotPath}'"))
        assertTrue(script.contains("SNAPSHOT_UID=10321"))
        assertTrue(script.contains("SNAPSHOT_GID=10321"))
        assertTrue(script.contains("chroot \"${'$'}ROOTFS\""))
        assertTrue(script.contains("/usr/bin/ps -e -o pid=,ppid=,pgid=,sid=,stat=,comm="))
        assertTrue(script.contains("PROCESS_TABLE_BEGIN"))
        assertTrue(script.contains("PROCESS_PS_EXIT="))
        assertTrue(script.contains("PROCESS_TABLE_END"))
        assertTrue(
            script.contains(
                "chown \"${'$'}SNAPSHOT_UID:${'$'}SNAPSHOT_GID\" \"${'$'}TMP_FILE\"",
            ),
        )
        assertTrue(script.contains("chmod 0600 \"${'$'}TMP_FILE\""))
        assertTrue(script.contains("SNAPSHOT_PERMISSION_FAILED"))
        assertTrue(script.contains("mv \"${'$'}TMP_FILE\" \"${'$'}SNAPSHOT_FILE\""))
        assertFalse(script.contains("/proc/[0-9]*"))
        assertFalse(script.contains("IFS= read -r STAT_LINE"))
    }

    @Test
    fun processTableProbeDiscardsShellPipes() {
        val request = PtyProcessProbeScriptBuilder.buildProcessTableRequest(
            rootPid = 30891,
            snapshotPath = "/data/user/0/example/files/runtime/tmp/process.snapshot",
            rootfsPath = "/data/user/0/example/files/runtime/rootfs/current",
            workingDirectory = "/data/user/0/example/files/runtime",
            snapshotOwnerUid = 10_321,
            snapshotOwnerGid = 10_321,
        )

        assertEquals(ShellOutputMode.DISCARD, request.outputMode)
        assertEquals(HostExecutionIdentity.ROOT, request.identity)
        assertEquals(5_000L, request.timeoutMillis)
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
              42       1      42      42 S     bash
        """.trimIndent()

        assertFalse(PtyProcessProbeParser.hasCompleteTable(output))
        assertTrue(PtyProcessProbeParser.parse(output, rootPid = 42).isEmpty())
    }

    @Test
    fun parsesProcpsRowsSelectsDescendantsAndEnrichesCommandLines() {
        val processOutput = """
            PROCESS_TABLE_BEGIN
              41000       1   41000   41000 S     unrelated
              30911   30910   30910   30910 S     sleep
              30891       1   30891   30891 Ss+   script
              30910   30891   30910   30910 S+    bash
            PROCESS_PS_EXIT=0
            PROCESS_TABLE_END
        """.trimIndent()
        val commandLineOutput = """
            CMDLINE_TABLE_BEGIN
            C|30891|/usr/bin/script -q
            C|30910|/bin/bash --noprofile --norc -i
            C|30911|sleep 60
            CMDLINE_TABLE_END
        """.trimIndent()

        val baseProcesses = PtyProcessProbeParser.parse(processOutput, rootPid = 30891)
        val commandLines = PtyProcessProbeParser.parseCommandLines(commandLineOutput)
        val processes = PtyProcessProbeParser.enrichCommandLines(baseProcesses, commandLines)

        assertEquals(0, PtyProcessProbeParser.parseProcessTableExitCode(processOutput))
        assertEquals(listOf(30891, 30910, 30911), processes.map(PtyProcessInfo::pid))
        assertEquals(30910, processes[1].processGroupId)
        assertEquals(30910, processes[1].sessionId)
        assertEquals("bash", processes[1].name)
        assertEquals("/bin/bash --noprofile --norc -i", processes[1].commandLine)
        assertEquals("sleep 60", processes[2].commandLine)
        assertEquals("S (sleeping)", processes[2].state)
    }

    @Test
    fun parsesNonZeroProcpsExitCode() {
        val output = """
            PROCESS_TABLE_BEGIN
            PROCESS_PS_EXIT=125
            PROCESS_TABLE_END
        """.trimIndent()

        assertEquals(125, PtyProcessProbeParser.parseProcessTableExitCode(output))
        assertTrue(PtyProcessProbeParser.parse(output, rootPid = 42).isEmpty())
    }

    @Test
    fun preservesSpacesInProcessNamesAndPipesInCommandLines() {
        val processOutput = """
            PROCESS_TABLE_BEGIN
               42       1      42      42 S     worker name
            PROCESS_PS_EXIT=0
            PROCESS_TABLE_END
        """.trimIndent()
        val commandLineOutput = """
            CMDLINE_TABLE_BEGIN
            C|42|worker --value=a|b
            CMDLINE_TABLE_END
        """.trimIndent()

        val base = PtyProcessProbeParser.parse(processOutput, rootPid = 42)
        val enriched = PtyProcessProbeParser.enrichCommandLines(
            base,
            PtyProcessProbeParser.parseCommandLines(commandLineOutput),
        )

        assertEquals(1, enriched.size)
        assertEquals("worker name", enriched.single().name)
        assertEquals("worker --value=a|b", enriched.single().commandLine)
        assertEquals(42, enriched.single().processGroupId)
    }

    @Test
    fun fallsBackToCommWhenCommandLineDisappears() {
        val processOutput = """
            PROCESS_TABLE_BEGIN
               42       1      42      42 S     bash
            PROCESS_PS_EXIT=0
            PROCESS_TABLE_END
        """.trimIndent()

        val processes = PtyProcessProbeParser.enrichCommandLines(
            PtyProcessProbeParser.parse(processOutput, rootPid = 42),
            emptyMap(),
        )

        assertEquals("[bash]", processes.single().commandLine)
    }

    @Test
    fun deduplicatesRepeatedProcessTableRecords() {
        val output = """
            PROCESS_TABLE_BEGIN
               42       1      42      42 S     bash
               42       1      42      42 S     bash
            PROCESS_PS_EXIT=0
            PROCESS_TABLE_END
        """.trimIndent()

        assertEquals(1, PtyProcessProbeParser.parse(output, rootPid = 42).size)
    }
}
