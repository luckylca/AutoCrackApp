package com.luckylca.autocrack.root

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
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
            supervisorScope {
                val process = ProcessBuilder(command)
                    .redirectErrorStream(false)
                    .start()

                // Read both pipes while the process is running. Waiting first can deadlock when
                // commands such as `pm list packages` fill the operating-system pipe buffer.
                // Each reader converts IOException into data so a timeout-driven stream close does
                // not cancel the parent scope and erase the real `timedOut=true` result.
                val stdoutDeferred = async(Dispatchers.IO) {
                    process.inputStream.captureRetainedText(MAX_RETAINED_OUTPUT_CHARS)
                }
                val stderrDeferred = async(Dispatchers.IO) {
                    process.errorStream.captureRetainedText(MAX_RETAINED_OUTPUT_CHARS)
                }

                val finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
                if (!finished) {
                    process.destroy()
                    if (!process.waitFor(GRACEFUL_STOP_MILLIS, TimeUnit.MILLISECONDS)) {
                        process.destroyForcibly()
                        process.waitFor(FORCED_STOP_MILLIS, TimeUnit.MILLISECONDS)
                    }

                    // Android may leave a descendant holding the inherited pipe descriptors even
                    // after the direct shell process is gone. Closing our local pipe endpoints is
                    // required to release the reader coroutines. The resulting IOException is an
                    // expected timeout cleanup event and is intentionally not reported as failure.
                    runCatching { process.inputStream.close() }
                    runCatching { process.errorStream.close() }
                    runCatching { process.outputStream.close() }
                }

                val stdoutCapture = stdoutDeferred.await()
                val stderrCapture = stderrDeferred.await()
                val captureFailure = stdoutCapture.failure ?: stderrCapture.failure

                if (finished && captureFailure != null) {
                    failedResult(label, captureFailure)
                } else {
                    CommandResult(
                        commandLabel = label,
                        exitCode = if (finished) process.exitValue() else null,
                        stdout = stdoutCapture.text.trim(),
                        stderr = stderrCapture.text.trim(),
                        timedOut = !finished,
                        failure = null,
                    )
                }
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

    private fun InputStream.captureRetainedText(maxRetainedChars: Int): StreamCapture {
        val retained = StringBuilder(min(maxRetainedChars, INITIAL_BUFFER_CHARS))
        val chunk = CharArray(READ_BUFFER_CHARS)
        var truncated = false

        val failure = try {
            bufferedReader().use { reader ->
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
            }
            null
        } catch (exception: IOException) {
            exception
        }

        if (truncated) {
            retained.append("\n...[output truncated by AutoCrackApp]")
        }
        return StreamCapture(text = retained.toString(), failure = failure)
    }

    private data class StreamCapture(
        val text: String,
        val failure: IOException?,
    )

    private companion object {
        const val MAX_RETAINED_OUTPUT_CHARS = 2_000_000
        const val INITIAL_BUFFER_CHARS = 16_384
        const val READ_BUFFER_CHARS = 8_192
        const val GRACEFUL_STOP_MILLIS = 250L
        const val FORCED_STOP_MILLIS = 1_000L
    }
}
