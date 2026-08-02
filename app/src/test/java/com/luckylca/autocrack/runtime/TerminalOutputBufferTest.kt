package com.luckylca.autocrack.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalOutputBufferTest {
    @Test
    fun stripsAnsiSequencesAndHandlesBackspace() {
        val buffer = TerminalOutputBuffer(maxCharacters = 2_048)

        val output = buffer.append("\u001B[31mRED\u001B[0m\r\nabc\bD\n")

        assertTrue(output.contains("RED\nabD"))
        assertFalse(output.contains("\u001B["))
    }

    @Test
    fun trimsOldOutputAtLineBoundary() {
        val buffer = TerminalOutputBuffer(maxCharacters = 1_024)
        val text = buildString {
            repeat(300) { index -> append("line-").append(index).append('\n') }
        }

        val output = buffer.append(text)

        assertTrue(output.startsWith("...[older terminal output trimmed]"))
        assertTrue(output.length < 1_200)
        assertTrue(output.contains("line-299"))
    }

    @Test
    fun buildsInteractiveRootChrootCommand() {
        val command = ChrootPtyCommandBuilder.build(
            "/data/data/com.luckylca.autocrack/files/runtime/rootfs/current",
        )

        assertTrue(command.contains("exec chroot"))
        assertTrue(command.contains("/usr/bin/env -i"))
        assertTrue(command.contains("TERM='xterm-256color'"))
        assertTrue(command.contains("PS1='autocrack:\\w# '"))
        assertTrue(command.contains("/bin/bash --noprofile --norc -i"))
        assertTrue(command.contains("cd -- /workspace"))
    }
}
