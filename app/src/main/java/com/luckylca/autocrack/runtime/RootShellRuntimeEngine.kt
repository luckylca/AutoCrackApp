package com.luckylca.autocrack.runtime

import java.io.File
import java.io.InputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal object RootShellProcessCommandFactory {
    fun build(
        identity: HostExecutionIdentity,
        suPath: String,
        script: String,
    ): List<String> = when (identity) {
        // Invoke su directly. An extra `/system/bin/sh -c "su -c ..."` wrapper can remain
        // alive on KernelSU after its root child has exited, leaving that child as a zombie and
        // preventing our authoritative exit-status trailer from running.
        HostExecutionIdentity.ROOT -> listOf(suPath, "-c", script)
        HostExecutionIdentity.APP -> listOf("/system/bin/sh", "-c", script)
    }
}

class RootShellRuntimeEngine(
    private val layout: RuntimeLayout,
    private val suPath: String = "/system/bin/su",
    private val onStage: (String) -> Unit = {},
    private val appUid: Int = android.os.Process.myUid(),
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
            val exitStatusFile = File(layout.tempRoot, "root-shell-exit-$requestId.txt").canonicalFile
            exitStatusFile.parentFile?.mkdirs()
            runCatching { exitStatusFile.delete() }
            val wrappedRequest = request.copy(
                command = wrapCommandWithExitStatus(request.command, exitStatusFile.path, appUid),
            )
            val script = ShellEscaper.buildHostScript(wrappedRequest)
            onStage("host_script_built")
            val command = RootShellProcessCommandFactory.build(
                identity = request.identity,
                suPath = suPath,
                script = script,
            )

            var process: Process? = null
            var failure: String? = null
            var timedOut = false
            var exitCode: Int? = null
            var stdoutCapture = OutputCapture("", false)
            var stderrCapture = OutputCapture("", false)

            try {
                // Stream readers are children of this scope. A normal Android/KernelSU teardown can
                // close a pipe after the wrapped command has already written its authoritative exit
                // status. Keep a reader IOException isolated until the explicit await logic below
                // can decide whether it is an expected teardown event or a real command failure.
                supervisorScope {
                    onStage("host_process_builder_enter")
                    val processBuilder = ProcessBuilder(command)
                        .redirectErrorStream(false)
                    if (request.outputMode == ShellOutputMode.DISCARD) {
                        processBuilder
                            .redirectOutput(NULL_DEVICE)
                            .redirectError(NULL_DEVICE)
                    }

                    onStage("host_process_start_enter")
                    process = processBuilder.start()
                    onStage("host_process_start_return")
                    val runningProcess = checkNotNull(process)
                    activeProcesses[requestId] = runningProcess
                    onStage("host_process_registered")

                    val stdoutDeferred = if (request.outputMode == ShellOutputMode.CAPTURE) {
                        async(Dispatchers.IO) {
                            runningProcess.inputStream.readBounded(MAX_RETAINED_OUTPUT_CHARS)
                        }
                    } else {
                        null
                    }
                    val stderrDeferred = if (request.outputMode == ShellOutputMode.CAPTURE) {
                        async(Dispatchers.IO) {
                            runningProcess.errorStream.readBounded(MAX_RETAINED_OUTPUT_CHARS)
                        }
                    } else {
                        null
                    }
                    val stdinDeferred = request.stdin?.let { stdin ->
                        async(Dispatchers.IO) {
                            runningProcess.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                                writer.write(stdin)
                            }
                        }
                    } ?: run {
                        // Most AutoCrack tool commands do not require stdin. Close the pipe
                        // immediately on the caller thread instead of creating another coroutine
                        // that can outlive a fast root child and leave a zombie process unreaped.
                        runCatching { runningProcess.outputStream.close() }
                        null
                    }

                    onStage("host_wait_enter")
                    val finished = waitForProcessOrExitStatus(
                        process = runningProcess,
                        timeoutMillis = request.timeoutMillis,
                        exitStatusFile = exitStatusFile,
                    )
                    when (finished) {
                        HostWaitOutcome.FINISHED -> onStage("host_wait_finished")
                        HostWaitOutcome.PROCESS_EXITED_WITHOUT_STATUS -> {
                            onStage("host_wait_process_exited_without_status")
                            failure = "Root shell process exited before writing authoritative exit-status file"
                            stopProcess(runningProcess)
                        }
                        HostWaitOutcome.EXIT_STATUS_READY -> {
                            onStage("host_wait_status_file_ready")
                            exitCode = readExitStatus(exitStatusFile)
                            // The wrapped root command writes the authoritative status before the
                            // Android/KernelSU Process wrapper necessarily reaches EOF. Give the
                            // pipe readers a short bounded chance to drain buffered output before
                            // tearing down that wrapper. This is a transport grace window only; it
                            // does not extend the command's requested execution timeout.
                            waitForStreamReaders(stdoutDeferred, stderrDeferred)
                            runCatching { runningProcess.destroyForcibly() }
                        }
                        HostWaitOutcome.TIMEOUT -> {
                            onStage("host_wait_timeout")
                            timedOut = true
                            stopProcess(runningProcess)
                        }
                    }

                    onStage("host_stdin_await_enter")
                    stdinDeferred?.await()
                    onStage("host_stdin_await_return")
                    onStage("host_stdout_await_enter")
                    stdoutDeferred?.let { deferred ->
                        val capture = deferred.await()
                        stdoutCapture = capture
                        capture.failure?.let { error ->
                            if (shouldIgnoreStreamAwaitFailure(finished, exitCode)) {
                                onStage("host_stdout_await_interrupted_ignored")
                            } else {
                                throw error
                            }
                        }
                    }
                    onStage("host_stdout_await_return")
                    onStage("host_stderr_await_enter")
                    stderrDeferred?.let { deferred ->
                        val capture = deferred.await()
                        stderrCapture = capture
                        capture.failure?.let { error ->
                            if (shouldIgnoreStreamAwaitFailure(finished, exitCode)) {
                                onStage("host_stderr_await_interrupted_ignored")
                            } else {
                                throw error
                            }
                        }
                    }
                    onStage("host_stderr_await_return")
                    if (exitCode == null && finished == HostWaitOutcome.FINISHED) {
                        exitCode = runCatching { runningProcess.exitValue() }.getOrNull()
                            ?: readExitStatus(exitStatusFile)
                    }
                    if (exitCode == null && finished == HostWaitOutcome.EXIT_STATUS_READY) {
                        exitCode = readExitStatus(exitStatusFile)
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
            onStage("host_append_audit_enter")
            appendAudit(
                result = result,
                environmentKeys = request.environment.keys.sorted(),
                outputMode = request.outputMode,
            )
            onStage("host_append_audit_return")
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

    private fun appendAudit(
        result: ShellCommandResult,
        environmentKeys: List<String>,
        outputMode: ShellOutputMode,
    ) {
        val json = JSONObject()
            .put("schemaVersion", 1)
            .put("requestId", result.requestId)
            .put("command", result.command)
            .put("workingDirectory", result.workingDirectory)
            .put("identity", result.identity.name)
            .put("outputMode", outputMode.name)
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
            appendJsonLineWithRotation(
                file = layout.shellAuditFile,
                jsonLine = json.toString(),
                maxBytes = SHELL_AUDIT_MAX_BYTES,
                backupCount = SHELL_AUDIT_BACKUP_COUNT,
            )
        }
    }

    private fun wrapCommandWithExitStatus(command: String, exitStatusPath: String, ownerUid: Int): String = """
        set +e
        (
        $command
        )
        __autoc_exit=$?
        printf '%s\n' "${'$'}__autoc_exit" > ${ShellEscaper.quote(exitStatusPath)} 2>/dev/null || true
        chown ${ownerUid}:${ownerUid} ${ShellEscaper.quote(exitStatusPath)} 2>/dev/null || true
        chmod 0644 ${ShellEscaper.quote(exitStatusPath)} 2>/dev/null || true
        exit "${'$'}__autoc_exit"
    """.trimIndent()

    private fun waitForProcessOrExitStatus(
        process: Process,
        timeoutMillis: Long,
        exitStatusFile: File,
    ): HostWaitOutcome {
        val deadline = System.currentTimeMillis() + timeoutMillis
        var statusFileObserved = false
        var pollCount = 0
        while (true) {
            pollCount += 1
            if (pollCount == 1 || pollCount % 20 == 0) {
                onStage("host_wait_poll:${exitStatusFile.name}:$pollCount:${exitStatusFile.exists()}:${exitStatusFile.length()}")
            }
            if (exitStatusFile.exists()) {
                if (!statusFileObserved) {
                    statusFileObserved = true
                    onStage("host_wait_status_exists:${exitStatusFile.length()}")
                }
                if (readExitStatus(exitStatusFile) != null) {
                    return HostWaitOutcome.EXIT_STATUS_READY
                }
            }
            if (isZombieProcess(process)) {
                onStage("host_wait_process_zombie_without_status:${exitStatusFile.name}")
                return HostWaitOutcome.PROCESS_EXITED_WITHOUT_STATUS
            }
            if (System.currentTimeMillis() >= deadline) {
                return HostWaitOutcome.TIMEOUT
            }
            Thread.sleep(HOST_WAIT_POLL_MILLIS)
        }
    }

    private fun readExitStatus(exitStatusFile: File): Int? = runCatching {
        exitStatusFile.takeIf(File::isFile)
            ?.readText(Charsets.UTF_8)
            ?.trim()
            ?.toIntOrNull()
    }.getOrNull()

    private fun isZombieProcess(process: Process): Boolean {
        val pid = readAndroidProcessPid(process) ?: return false
        return readLinuxProcessState(pid) == 'Z'
    }

    private fun readAndroidProcessPid(process: Process): Long? = runCatching {
        val field = process.javaClass.getDeclaredField("pid")
        field.isAccessible = true
        (field.get(process) as? Number)?.toLong()
    }.getOrNull()

    private fun readLinuxProcessState(pid: Long): Char? = runCatching {
        val stat = File("/proc/$pid/stat").readText(Charsets.UTF_8)
        stat.substringAfterLast(") ")
            .firstOrNull { it != ' ' }
    }.getOrNull()

    private fun stopProcess(process: Process) {
        // KernelSU/Android can leave the direct Process wrapper in a state where
        // waitFor()/isAlive/sleep-based reaping blocks even after the root child
        // has already written the authoritative exit-code file. Root/chroot
        // orphan cleanup is handled separately with the request token, so this
        // method must be best-effort and non-blocking.
        runCatching { process.destroy() }
        runCatching { process.destroyForcibly() }
    }

    private fun InputStream.readBounded(maxRetainedChars: Int): OutputCapture {
        val retained = StringBuilder(min(maxRetainedChars, INITIAL_BUFFER_CHARS))
        val chunk = CharArray(READ_BUFFER_CHARS)
        var truncated = false
        var failure: IOException? = null

        try {
            bufferedReader(Charsets.UTF_8).use { reader ->
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
            }
        } catch (exception: IOException) {
            // Preserve bytes already drained before an intentional Process teardown closes the
            // Android pipe. The caller decides from HostWaitOutcome whether this IOException is an
            // expected transport-close event or a real command failure.
            failure = exception
        }

        if (truncated) {
            retained.append("\n...[output truncated by AutoCrackApp]")
        }
        return OutputCapture(retained.toString(), truncated, failure)
    }

    private fun waitForStreamReaders(
        stdout: Deferred<OutputCapture>?,
        stderr: Deferred<OutputCapture>?,
    ) {
        if (stdout == null && stderr == null) return
        val deadline = System.currentTimeMillis() + EXIT_STATUS_STREAM_DRAIN_GRACE_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if ((stdout == null || stdout.isCompleted) && (stderr == null || stderr.isCompleted)) return
            Thread.sleep(STREAM_DRAIN_POLL_MILLIS)
        }
    }

    private fun shouldIgnoreStreamAwaitFailure(outcome: HostWaitOutcome, authoritativeExitCode: Int?): Boolean =
        (outcome == HostWaitOutcome.EXIT_STATUS_READY && authoritativeExitCode != null) ||
            outcome == HostWaitOutcome.TIMEOUT

    private enum class HostWaitOutcome {
        FINISHED,
        PROCESS_EXITED_WITHOUT_STATUS,
        EXIT_STATUS_READY,
        TIMEOUT,
    }

    private data class OutputCapture(
        val text: String,
        val truncated: Boolean,
        val failure: IOException? = null,
    )

    private companion object {
        val NULL_DEVICE = File("/dev/null")
        const val MAX_RETAINED_OUTPUT_CHARS = 1_000_000
        const val INITIAL_BUFFER_CHARS = 16_384
        const val READ_BUFFER_CHARS = 8_192
        const val HOST_WAIT_POLL_MILLIS = 100L
        const val EXIT_STATUS_STREAM_DRAIN_GRACE_MILLIS = 250L
        const val STREAM_DRAIN_POLL_MILLIS = 10L
        const val GRACEFUL_STOP_MILLIS = 300L
        const val FORCED_STOP_MILLIS = 1_000L
        const val SHELL_AUDIT_MAX_BYTES = 5L * 1_024L * 1_024L
        const val SHELL_AUDIT_BACKUP_COUNT = 3
    }
}
