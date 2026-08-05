package com.luckylca.autocrack.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicHostOutputParserTest {
    @Test
    fun parseProcesses_readsBoundedTsvOutput() {
        val processes = DynamicHostOutputParser.parseProcesses(
            """
            pid	ppid	uid	state	name	cmdline
            42	1	10123	S (sleeping)	com.example	/system/bin/app_process com.example
            7	2	0	R (running)	worker	
            """.trimIndent(),
        )

        assertEquals(listOf(7, 42), processes.map(HostProcessSummary::pid))
        assertEquals(10123, processes.last().uid)
        assertEquals("com.example", processes.last().name)
        assertEquals("", processes.first().commandLine)
    }

    @Test
    fun parseLoadedModules_aggregatesSegmentsAndExecutableFlag() {
        val modules = DynamicHostOutputParser.parseLoadedModules(
            """
            70000000-70001000 r--p 00000000 00:00 1 /system/lib64/libc.so
            70001000-70005000 r-xp 00001000 00:00 1 /system/lib64/libc.so
            71000000-71002000 rw-p 00000000 00:00 2 /data/app/com.example/lib/arm64/libtarget.so
            72000000-72001000 r-xp 00000000 00:00 0 [vdso]
            """.trimIndent(),
        )

        assertEquals(2, modules.size)
        val libc = modules.first { it.path.endsWith("libc.so") }
        assertEquals(2, libc.segmentCount)
        assertTrue(libc.executable)
        assertEquals(0x5000L, libc.mappedBytes)

        val target = modules.first { it.path.endsWith("libtarget.so") }
        assertFalse(target.executable)
        assertEquals(1, target.segmentCount)
    }

    @Test
    fun parseLoadedModules_ignoresAnonymousAndMalformedMappings() {
        val modules = DynamicHostOutputParser.parseLoadedModules(
            """
            malformed
            1000-2000 rw-p 00000000 00:00 0
            3000-4000 r-xp 00000000 00:00 0 [anon:dalvik]
            """.trimIndent(),
        )

        assertTrue(modules.isEmpty())
    }
}
