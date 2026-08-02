package com.luckylca.autocrack.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellEscaperTest {
    @Test
    fun quotePreservesSpacesAndSingleQuotes() {
        assertEquals("'a b'\"'\"'c'", ShellEscaper.quote("a b'c"))
    }

    @Test
    fun buildHostScriptUsesRequestedWorkingDirectoryAndEnvironment() {
        val request = ShellCommandRequest(
            command = "printf '%s' \"\$VALUE\"",
            workingDirectory = "/data/user/0/com.example/files/work space",
            environment = mapOf("VALUE" to "hello ' runtime"),
            timeoutMillis = 1_000L,
        )

        val script = ShellEscaper.buildHostScript(request)

        assertTrue(script.contains("cd -- '/data/user/0/com.example/files/work space'"))
        assertTrue(script.contains("export VALUE='hello '\"'\"' runtime'"))
        assertTrue(script.contains("exec /system/bin/sh -c"))
    }

    @Test
    fun requestRejectsInvalidEnvironmentVariableName() {
        assertThrows(IllegalArgumentException::class.java) {
            ShellCommandRequest(
                command = "id",
                workingDirectory = "/tmp",
                environment = mapOf("BAD-NAME" to "value"),
            )
        }
    }

    @Test
    fun requestRejectsExcessiveTimeout() {
        assertThrows(IllegalArgumentException::class.java) {
            ShellCommandRequest(
                command = "sleep 1",
                workingDirectory = "/tmp",
                timeoutMillis = ShellCommandRequest.MAX_TIMEOUT_MILLIS + 1L,
            )
        }
    }
}
