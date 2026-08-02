package com.luckylca.autocrack.runtime

import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class RootShellRuntimeEngine(
    private val layout: RuntimeLayout,
    private val suPath: String = "/system/bin/su",
) : RuntimeEngine {
    override val mode: RuntimeCapabilityMode = RuntimeCapabilityMode.FULL_ROOT

    private val activeProcesses = ConcurrentHashMap<String, Process>()
    private val cancelledRequests = ConcurrentHashMap.newKeySet<String>()
    private val auditLock = Any()

    override suspend fun execute(request: ShellCommandRequest): ShellCommandResult =
        withContext(Dispatchers.IO) {
            layout.initialize()
            val requestId = UUID.randomUUID().toString()
            val startedAt = System.currentTimeMillis()
            val script = ShellEscaper.buildHostScript(request)
            val command = when (request.identity) {
                HostExecutionIdentity.ROOT -> listOf(suPath, "-c", script)
                HostExecutionIdentity.APP -> listOf("/system/bin/sh", "-c", script)
            }

            var process: Process? = null
            var failure: String? = null
            var timedOut = false
            var exitCode: Int? = null
            var stdoutCapture = OutputCapture("", false)
            var stderrCapture = OutputCapture("", false)

            try {
                coroutineScope {
                    process = ProcessBuilder(command)
                        .redirectErrorStream(false)
                        .start()
                    val runningProcess = checkNotNull(process)
                    activeProcesses[requestId] = runningProcess

                    val stdoutDeferred = async(Dispatchers.IO) {
                        runningProcess.inputStream.readBounded(MAX_RETAINED_OUTPUT_CHARS)
                    }
                    val stderrDeferred = async(Dispatchers.IO) {
                        runningProcess.errorStream.readBounded(MAX_RETAINED_OUTPUT_CHARS)
                    }
                    val stdinDeferred = async(Dispatchers.IO) {
                        runningProcess.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                            request.stdin?.let { stdin -> writer.write(stdin) }
                        }
                    }

                    val finished = runningProcess.waitFor(
                        request.timeoutMillis,
                        TimeUnit.MILLISECONDS,
                    )
                    if (!finished) {
                        timedOut = true
                        stopProcess(runningProcess)
                    }

                    stdinDeferred.await()
                    stdoutCapture = stdoutDeferred.await()
                    stderrCapture = stderrDeferred.await()
                    if (finished || !runningProcess.isAlive) {
                        exitCode = runCatching { runningProcess.exitValue() }.getOrNull()
                    }
                }
            } catch (exception: Exception) {
                failure = exception.message ?: exception::class.java.name
                process?.let(::stopProcess)
            } finally {
                activeProcesses.remove(requestId)
            }

            val completedAt = System.currentTimeMillis()
            val cancelled = cancelledRequests.remove(requestId)
            val result = ShellCommandResult(
                requestId = requestId,
                command = request.command,
                workingDirectory = request.workingDirectory,
                identity = request.identity,
                exitCode = exitCode,
                stdout = stdoutCapture.text.trimEnd(),
                stderr = stderrCapture.text.trimEnd(),
                startedAtEpochMillis = startedAt,
                completedAtEpochMillis = completedAt,
                timedOut = timedOut,
                cancelled = cancelled,
                stdoutTruncated = stdoutCapture.truncated,
                stderrTruncated = stderrCapture.truncated,
                failure = failure,
                auditFilePath = layout.shellAuditFile.path,
            )
            appendAudit(result, request.environment.keys.sorted())
            result
        }

    fun cancelAll(): Int {
        val entries = activeProcesses.entries.toList()
        entries.forEach { (requestId, process) ->
            cancelledRequests += requestId
            stopProcess(process)
        }
        return entries.size
    }

    fun activeRequestIds(): List<String> = activeProcesses.keys().toList().sorted()

    private fun appendAudit(result: ShellCommandResult, environmentKeys: List<String>) {
        val json = JSONObject()
            .put("schemaVersion", 1)
            .put("requestId", result.requestId)
            .put("command", result.command)
            .put("workingDirectory", result.workingDirectory)
            .put("identity", result.identity.name)
            .put("environmentKeys", JSONArray(environmentKeys))
            .put("exitCode", result.exitCode ?: JSONObject.NULL)
            .put("startedAtEpochMillis", result.startedAtEpochMillis)
            .put("completedAtEpochMillis", result.completedAtEpochMillis)
            .put("durationMillis", result.durationMillis)
            .put("timedOut", result.timedOut)
            .put("cancelled", result.cancelled)
            .put("stdoutChars", result.stdout.length)
            .put("stderrChars", result.stderr.length)
            .put("stdoutTruncated", result.stdoutTruncated)
            .put("stderrTruncated", result.stderrTruncated)
            .put("failure", result.failure ?: JSONObject.NULL)

        synchronized(auditLock) {
            layout.auditRoot.mkdirs()
            layout.shellAuditFile.appendText(json.toString() + "\n", Charsets.UTF_8)
        }
    }

    private fun stopProcess(process: Process) {
        process.destroy()
        if (!runCatching {
                process.waitFor(GRACEFUL_STOP_MILLIS, TimeUnit.MILLISECONDS)
            }.getOrDefault(false)
        ) {
            process.destroyForcibly()
            runCatching { process.waitFor(FORCED_STOP_MILLIS, TimeUnit.MILLISECONDS) }
        }
    }

    private fun InputStream.readBounded(maxRetainedChars: Int): OutputCapture =
        bufferedReader(Charsets.UTF_8).use { reader ->
            val retained = StringBuilder(min(maxRetainedChars, INITIAL_BUFFER_CHARS))
            val chunk = CharArray(READ_BUFFER_CHARS)
            var truncated = false

            while (true) {
                val count = reader.read(chunk)
                if (count < 0) break
                val remaining = maxRetainedChars - retained.length
                if (remaining > 0) {
                    val retainedCount = min(remaining, count)
                    retained.append(chunk, 0, retainedCount)
                    if (retainedCount < count) truncated = true
                } else {
                    truncated = true
                }
            }

            if (truncated) {
                retained.append("\n...[output truncated by AutoCrackApp]")
            }
            OutputCapture(retained.toString(), truncated)
        }

    private data class OutputCapture(
        val text: String,
        val truncated: Boolean,
    )

    private companion object {
        const val MAX_RETAINED_OUTPUT_CHARS = 1_000_000
        const val INITIAL_BUFFER_CHARS = 16_384
        const val READ_BUFFER_CHARS = 8_192
        const val GRACEFUL_STOP_MILLIS = 300L
        const val FORCED_STOP_MILLIS = 1_000L
    }
}
