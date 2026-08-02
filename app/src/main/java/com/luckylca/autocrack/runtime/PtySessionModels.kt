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

data class PtyProcessInfo(
    val pid: Int,
    val parentPid: Int,
    val processGroupId: Int,
    val sessionId: Int,
    val state: String,
    val name: String,
    val commandLine: String,
)

data class PtyProcessTreeSnapshot(
    val rootPid: Int? = null,
    val processes: List<PtyProcessInfo> = emptyList(),
    val refreshedAtEpochMillis: Long? = null,
    val failure: String? = null,
)

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
    val processTree: PtyProcessTreeSnapshot = PtyProcessTreeSnapshot(),
    val cleanupExitCode: Int? = null,
    val cleanupOutput: String? = null,
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
    private val csiParameters = StringBuilder()
    private var escapeState = EscapeState.NORMAL
    private var cursor = 0

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
        csiParameters.clear()
        escapeState = EscapeState.NORMAL
        cursor = 0
    }

    private fun accept(character: Char) {
        when (escapeState) {
            EscapeState.NORMAL -> acceptNormal(character)
            EscapeState.ESCAPE -> when (character) {
                '[' -> {
                    csiParameters.clear()
                    escapeState = EscapeState.CSI
                }
                ']' -> escapeState = EscapeState.OSC
                else -> escapeState = EscapeState.NORMAL
            }
            EscapeState.CSI -> {
                if (character in '@'..'~') {
                    applyCsi(character)
                    csiParameters.clear()
                    escapeState = EscapeState.NORMAL
                } else if (csiParameters.length < MAX_CSI_PARAMETER_CHARS) {
                    csiParameters.append(character)
                }
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
            '\r' -> cursor = currentLineStart()
            '\b' -> cursor = (cursor - 1).coerceAtLeast(currentLineStart())
            '\n' -> moveToNextLine()
            '\t' -> writeCharacter(character)
            else -> {
                if (character.code >= SPACE_CODE && character != '\u007F') {
                    writeCharacter(character)
                }
            }
        }
    }

    private fun writeCharacter(character: Char) {
        cursor = cursor.coerceIn(0, output.length)
        if (cursor < output.length && output[cursor] != '\n') {
            output.setCharAt(cursor, character)
        } else {
            output.insert(cursor, character)
        }
        cursor += 1
    }

    private fun moveToNextLine() {
        val lineEnd = currentLineEnd()
        if (lineEnd < output.length && output[lineEnd] == '\n') {
            cursor = lineEnd + 1
        } else {
            output.insert(lineEnd, '\n')
            cursor = lineEnd + 1
        }
    }

    private fun applyCsi(finalCharacter: Char) {
        val parameters = csiParameters.toString()
            .trimStart('?', '>', '!')
            .split(';')
            .mapNotNull(String::toIntOrNull)
        val first = parameters.firstOrNull() ?: 0
        when (finalCharacter) {
            'K' -> eraseInLine(first)
            'J' -> if (first == 2 || first == 3) clear()
            'G' -> setColumn((if (first <= 0) 1 else first) - 1)
            'C' -> moveCursorRight(if (first <= 0) 1 else first)
            'D' -> moveCursorLeft(if (first <= 0) 1 else first)
        }
    }

    private fun eraseInLine(mode: Int) {
        val lineStart = currentLineStart()
        val lineEnd = currentLineEnd()
        when (mode) {
            1 -> {
                output.delete(lineStart, cursor.coerceAtMost(lineEnd))
                cursor = lineStart
            }
            2 -> {
                output.delete(lineStart, lineEnd)
                cursor = lineStart
            }
            else -> output.delete(cursor.coerceAtMost(lineEnd), lineEnd)
        }
    }

    private fun setColumn(column: Int) {
        cursor = (currentLineStart() + column.coerceAtLeast(0)).coerceAtMost(currentLineEnd())
    }

    private fun moveCursorRight(count: Int) {
        cursor = (cursor + count).coerceAtMost(currentLineEnd())
    }

    private fun moveCursorLeft(count: Int) {
        cursor = (cursor - count).coerceAtLeast(currentLineStart())
    }

    private fun currentLineStart(): Int {
        val searchFrom = (cursor - 1).coerceAtMost(output.lastIndex)
        if (searchFrom < 0) return 0
        return output.lastIndexOf("\n", searchFrom) + 1
    }

    private fun currentLineEnd(): Int {
        val newline = output.indexOf("\n", cursor.coerceAtMost(output.length))
        return if (newline >= 0) newline else output.length
    }

    private fun trimToLimit() {
        if (output.length <= maxCharacters) return
        var removeCount = output.length - maxCharacters
        val nextNewline = output.indexOf("\n", removeCount)
        if (nextNewline >= 0) removeCount = nextNewline + 1
        removeCount = removeCount.coerceAtMost(output.length)
        output.delete(0, removeCount)
        cursor = (cursor - removeCount).coerceAtLeast(0)
        output.insert(0, TRIM_MARKER)
        cursor += TRIM_MARKER.length
    }

    private companion object {
        const val SPACE_CODE = 0x20
        const val MAX_CSI_PARAMETER_CHARS = 64
        const val TRIM_MARKER = "...[older terminal output trimmed]\n"
    }
}

const val DEFAULT_TERMINAL_ROWS = 32
const val DEFAULT_TERMINAL_COLUMNS = 100
const val MAX_TERMINAL_OUTPUT_CHARS = 300_000
