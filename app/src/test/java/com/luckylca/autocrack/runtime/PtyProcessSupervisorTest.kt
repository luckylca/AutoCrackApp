package com.luckylca.autocrack.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PtyProcessSupervisorTest {
    @Test
    fun buildsPidScopedRecursiveProcProbe() {
        val script = PtyProcessProbeScriptBuilder.build(30891)

        assertTrue(script.contains("ROOT_PID=30891"))
        assertTrue(script.contains("/proc/\${PID}/task/\${PID}/children"))
        assertTrue(script.contains("PROCESS_TREE_BEGIN"))
        assertTrue(script.contains("PROCESS_TREE_END"))
    }

    @Test
    fun parsesProcessTreeRecordsAndIgnoresNoise() {
        val output = """
            unrelated output
            PROCESS_TREE_BEGIN
            P|30891|1|30891|30891|S (sleeping)|su|/system/bin/su -c chroot
            P|30910|30891|30891|30891|S (sleeping)|bash|/bin/bash --noprofile --norc -i
            malformed
            PROCESS_TREE_END
            P|999|1|1|1|R|ignored|ignored
        """.trimIndent()

        val processes = PtyProcessProbeParser.parse(output)

        assertEquals(2, processes.size)
        assertEquals(30891, processes[0].pid)
        assertEquals(30910, processes[1].pid)
        assertEquals("bash", processes[1].name)
        assertEquals("/bin/bash --noprofile --norc -i", processes[1].commandLine)
    }

    @Test
    fun deduplicatesRepeatedPidRecords() {
        val output = """
            PROCESS_TREE_BEGIN
            P|42|1|42|42|S|bash|bash
            P|42|1|42|42|S|bash|bash
            PROCESS_TREE_END
        """.trimIndent()

        assertEquals(1, PtyProcessProbeParser.parse(output).size)
    }
}
