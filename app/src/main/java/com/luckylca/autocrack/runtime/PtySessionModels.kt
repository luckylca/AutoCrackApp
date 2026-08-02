package com.luckylca.autocrack.runtime

import java.util.UUID

enum class PtySessionState {
    IDLE,
    STARTING,
    RUNNING,
    CLOSING,
    EXITED,
    FAILED,
}

data class PtySessionSnapshot(
    val sessionId: String? = null,
    val state: PtySessionState = PtySessionState.IDLE,
    val pid: Int? = null,
    val rows: Int = DEFAULT_TERMINAL_ROWS,
    val columns: Int = DEFAULT_TERMINAL_COLUMNS,
    val openedAtEpochMillis: Long? = null,
    val completedAtEpochMillis: Long? = null,
    val exitCode: Int? = null,
    val output: String = "",
    val outputVersion: Long = 0L,
    val bytesRead: Long = 0L,
    val bytesWritten: Long = 0L,
    val transcriptPath: String? = null,
    val auditPath: String? = null,
    val failure: String? = null,
) {
    val isRunning: Boolean
        get() = state == PtySessionState.STARTING ||
            state == PtySessionState.RUNNING ||
            state == PtySessionState.CLOSING
}

internal data class ActivePtySession(
    val sessionId: String = UUID.randomUUID().toString(),
    val handle: Long,
    val pid: Int,
    val openedAtEpochMillis: Long,
    val transcriptPath: String,
    val rows: Int,
    val columns: Int,
)

internal class TerminalOutputBuffer(
    private val maxCharacters: Int = MAX_TERMINAL_OUTPUT_CHARS,
) {
    private enum class EscapeState {
        NORMAL,
        ESCAPE,
        CSI,
        OSC,
        OSC_ESCAPE,
    }

    private val output = StringBuilder()
    private var escapeState = EscapeState.NORMAL

    init {
        require(maxCharacters >= 1_024) { "终端输出上限过小" }
    }

    fun append(text: String): String {
        text.forEach(::accept)
        trimToLimit()
        return output.toString()
    }

    fun snapshot(): String = output.toString()

    fun clear() {
        output.clear()
        escapeState = EscapeState.NORMAL
    }

    private fun accept(character: Char) {
        when (escapeState) {
            EscapeState.NORMAL -> acceptNormal(character)
            EscapeState.ESCAPE -> when (character) {
                '[' -> escapeState = EscapeState.CSI
                ']' -> escapeState = EscapeState.OSC
                else -> escapeState = EscapeState.NORMAL
            }
            EscapeState.CSI -> {
                if (character in '@'..'~') escapeState = EscapeState.NORMAL
            }
            EscapeState.OSC -> when (character) {
                '\u0007' -> escapeState = EscapeState.NORMAL
                '\u001B' -> escapeState = EscapeState.OSC_ESCAPE
            }
            EscapeState.OSC_ESCAPE -> {
                escapeState = if (character == '\\') EscapeState.NORMAL else EscapeState.OSC
            }
        }
    }

    private fun acceptNormal(character: Char) {
        when (character) {
            '\u001B' -> escapeState = EscapeState.ESCAPE
            '\r' -> Unit
            '\b' -> {
                if (output.isNotEmpty() && output.last() != '\n') {
                    output.deleteCharAt(output.lastIndex)
                }
            }
            '\n', '\t' -> output.append(character)
            else -> {
                if (character.code >= SPACE_CODE && character != '\u007F') {
                    output.append(character)
                }
            }
        }
    }

    private fun trimToLimit() {
        if (output.length <= maxCharacters) return
        var removeCount = output.length - maxCharacters
        val nextNewline = output.indexOf("\n", removeCount)
        if (nextNewline >= 0) removeCount = nextNewline + 1
        output.delete(0, removeCount.coerceAtMost(output.length))
        output.insert(0, "...[older terminal output trimmed]\n")
    }

    private companion object {
        const val SPACE_CODE = 0x20
    }
}

const val DEFAULT_TERMINAL_ROWS = 32
const val DEFAULT_TERMINAL_COLUMNS = 100
const val MAX_TERMINAL_OUTPUT_CHARS = 300_000
