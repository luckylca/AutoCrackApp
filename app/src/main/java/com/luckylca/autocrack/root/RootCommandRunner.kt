package com.luckylca.autocrack.root

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

interface RootCommandRunner {
    suspend fun run(
        command: List<String>,
        label: String,
        timeoutMillis: Long,
    ): CommandResult
}

class ProcessRootCommandRunner : RootCommandRunner {
    override suspend fun run(
        command: List<String>,
        label: String,
        timeoutMillis: Long,
    ): CommandResult = withContext(Dispatchers.IO) {
        require(command.isNotEmpty()) { "Command must not be empty" }
        require(timeoutMillis > 0) { "Timeout must be positive" }

        try {
            coroutineScope {
                val process = ProcessBuilder(command)
                    .redirectErrorStream(false)
                    .start()

                // Read both pipes while the process is running. Waiting first can deadlock when
                // commands such as `pm list packages` fill the operating-system pipe buffer.
                val stdoutDeferred = async(Dispatchers.IO) {
                    process.inputStream.readRetainedText(MAX_RETAINED_OUTPUT_CHARS)
                }
                val stderrDeferred = async(Dispatchers.IO) {
                    process.errorStream.readRetainedText(MAX_RETAINED_OUTPUT_CHARS)
                }

                val finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
                if (!finished) {
                    process.destroy()
                    if (!process.waitFor(GRACEFUL_STOP_MILLIS, TimeUnit.MILLISECONDS)) {
                        process.destroyForcibly()
                        process.waitFor(FORCED_STOP_MILLIS, TimeUnit.MILLISECONDS)
                    }
                }

                CommandResult(
                    commandLabel = label,
                    exitCode = if (finished) process.exitValue() else null,
                    stdout = stdoutDeferred.await().trim(),
                    stderr = stderrDeferred.await().trim(),
                    timedOut = !finished,
                    failure = null,
                )
            }
        } catch (exception: IOException) {
            failedResult(label, exception)
        } catch (exception: SecurityException) {
            failedResult(label, exception)
        }
    }

    private fun failedResult(label: String, exception: Exception): CommandResult = CommandResult(
        commandLabel = label,
        exitCode = null,
        stdout = "",
        stderr = "",
        timedOut = false,
        failure = exception.message ?: exception::class.java.simpleName,
    )

    private fun InputStream.readRetainedText(maxRetainedChars: Int): String =
        bufferedReader().use { reader ->
            val retained = StringBuilder(min(maxRetainedChars, INITIAL_BUFFER_CHARS))
            val chunk = CharArray(READ_BUFFER_CHARS)
            var truncated = false

            while (true) {
                val count = reader.read(chunk)
                if (count < 0) break

                val remaining = maxRetainedChars - retained.length
                if (remaining > 0) {
                    val retainedCount = min(remaining, count)
                    retained.append(String(chunk, 0, retainedCount))
                    truncated = truncated || retainedCount < count
                } else {
                    truncated = true
                }
            }

            if (truncated) {
                retained.append("\n...[output truncated by AutoCrackApp]")
            }
            retained.toString()
        }

    private companion object {
        const val MAX_RETAINED_OUTPUT_CHARS = 2_000_000
        const val INITIAL_BUFFER_CHARS = 16_384
        const val READ_BUFFER_CHARS = 8_192
        const val GRACEFUL_STOP_MILLIS = 250L
        const val FORCED_STOP_MILLIS = 1_000L
    }
}
