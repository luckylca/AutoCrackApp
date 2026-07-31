package com.luckylca.autocrack.root

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
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
            val process = ProcessBuilder(command)
                .redirectErrorStream(false)
                .start()

            val finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroy()
                if (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                }
            }

            CommandResult(
                commandLabel = label,
                exitCode = if (finished) process.exitValue() else null,
                stdout = process.inputStream.bufferedReader().use { it.readText() }.trim(),
                stderr = process.errorStream.bufferedReader().use { it.readText() }.trim(),
                timedOut = !finished,
                failure = null,
            )
        } catch (exception: IOException) {
            CommandResult(
                commandLabel = label,
                exitCode = null,
                stdout = "",
                stderr = "",
                timedOut = false,
                failure = exception.message ?: exception::class.java.simpleName,
            )
        } catch (exception: SecurityException) {
            CommandResult(
                commandLabel = label,
                exitCode = null,
                stdout = "",
                stderr = "",
                timedOut = false,
                failure = exception.message ?: exception::class.java.simpleName,
            )
        }
    }
}
