package com.luckylca.autocrack.runtime

import com.luckylca.autocrack.apk.PackageOutputParser
import com.luckylca.autocrack.root.ProcessRootCommandRunner
import com.luckylca.autocrack.root.RootCommandRunner
import com.luckylca.autocrack.root.RootDetector
import com.luckylca.autocrack.root.RootToolCommand
import com.luckylca.autocrack.root.RootToolCommandFactory
import com.luckylca.autocrack.root.RootToolExecutor
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class HostLogcatSessionSnapshot(
    val sessionId: String,
    val packageName: String,
    val pid: Int,
    val running: Boolean,
    val startedAtEpochMillis: Long,
    val stoppedAtEpochMillis: Long?,
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val outputTruncated: Boolean,
    val logFile: File,
    val failure: String?,
)

object HostLogcatCommandFactory {
    fun build(suPath: String, packageName: String, pid: Int): List<String> {
        require(suPath.isNotBlank()) { "su path must not be blank" }
        require('\u0000' !in suPath && '\n' !in suPath && '\r' !in suPath) {
            "su path contains an invalid character"
        }
        PackageOutputParser.requireValidPackageName(packageName)
        require(pid > 0) { "PID must be positive" }
        val quotedPackage = RootToolCommandFactory.shellQuote(packageName)
        val shellCommand = """
            expected_package=$quotedPackage
            proc=/proc/$pid
            [ -d "${'$'}proc" ] || { echo 'PROCESS_NOT_FOUND pid=$pid' >&2; exit 3; }
            cmdline=${'$'}(tr '\000' ' ' < "${'$'}proc/cmdline" 2>/dev/null | tr '\t\r\n' '   ')
            argv0=${'$'}{cmdline%% *}
            case "${'$'}argv0" in
              "${'$'}expected_package"|"${'$'}expected_package":*) ;;
              *) echo 'IDENTITY_MISMATCH pid=$pid' >&2; exit 5 ;;
            esac
            exec logcat --pid=$pid -v threadtime
        """.trimIndent()
        return listOf(suPath, "-c", shellCommand)
    }
}

object HostLogcatIdentityMatcher {
    fun matches(packageName: String, identityOutput: String): Boolean {
        PackageOutputParser.requireValidPackageName(packageName)
        val commandLine = identityOutput
            .lineSequence()
            .firstOrNull { it.startsWith("cmdline=") }
            ?.removePrefix("cmdline=")
            ?.trim()
            .orEmpty()
        if (commandLine.isBlank()) return false
        val argv0 = commandLine.substringBefore(' ')
        return argv0 == packageName || argv0.startsWith("$packageName:")
    }
}

class HostLogcatSessionManager(
    private val layout: RuntimeLayout,
    private val rootDetector: RootDetector,
    private val runner: RootCommandRunner = ProcessRootCommandRunner(),
) {
    val auditFile: File = File(layout.auditRoot, "dynamic-logcat.jsonl")
    val sessionRoot: File = File(layout.sessionsRoot, "logcat")

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeSession: MutableSession? = null
    private var starting = false

    suspend fun start(packageName: String, pid: Int): HostLogcatSessionSnapshot {
        PackageOutputParser.requireValidPackageName(packageName)
        require(pid > 0) { "PID 必须是正整数" }
        layout.initialize()
        sessionRoot.mkdirs()

        synchronized(lock) {
            require(!starting && activeSession?.process?.isAlive != true) {
                "已有 Logcat 会话正在运行或启动，请先停止当前会话"
            }
            starting = true
        }

        try {
            val rootStatus = rootDetector.inspect()
            require(rootStatus.isRootGranted) {
                rootStatus.diagnostic ?: "Logcat 会话需要 Root 权限"
            }
            val suPath = requireNotNull(rootStatus.suPath) { "Root 已授权但没有可用的 su 路径" }

            val identity = RootToolExecutor(runner, suPath)
                .execute(RootToolCommand.ReadProcessIdentity(pid))
            require(identity.succeeded) {
                identity.failure ?: identity.stderr.ifBlank { "无法读取 PID $pid 身份" }
            }
            require(HostLogcatIdentityMatcher.matches(packageName, identity.stdout)) {
                "PID $pid 当前身份不属于包 $packageName；已拒绝启动 Logcat 会话"
            }

            val sessionId = UUID.randomUUID().toString()
            val logFile = File(sessionRoot, "$sessionId.log")
            val process = withContext(Dispatchers.IO) {
                ProcessBuilder(HostLogcatCommandFactory.build(suPath, packageName, pid))
                    .redirectErrorStream(false)
                    .start()
            }
            val session = MutableSession(
                sessionId = sessionId,
                packageName = packageName,
                pid = pid,
                process = process,
                startedAtEpochMillis = System.currentTimeMillis(),
                logFile = logFile,
            )

            synchronized(lock) {
                activeSession = session
            }
            appendAudit(event = "start", session = session, helperSignalSent = false)

            scope.launch { drainStream(session, stderr = false) }
            scope.launch { drainStream(session, stderr = true) }
            scope.launch { awaitExit(session) }

            return snapshot(session)
        } finally {
            synchronized(lock) { starting = false }
        }
    }

    fun snapshot(): HostLogcatSessionSnapshot? = synchronized(lock) {
        activeSession?.let(::snapshotLocked)
    }

    suspend fun stop(): HostLogcatSessionSnapshot? = withContext(Dispatchers.IO) {
        val session = synchronized(lock) { activeSession } ?: return@withContext null
        var helperSignalSent = false
        if (session.process.isAlive) {
            helperSignalSent = true
            session.stopRequested = true
            session.process.destroy()
            if (!session.process.waitFor(STOP_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
                session.process.destroyForcibly()
                session.process.waitFor(STOP_FORCE_MILLIS, TimeUnit.MILLISECONDS)
            }
        }
        synchronized(lock) {
            if (session.stoppedAtEpochMillis == null && !session.process.isAlive) {
                session.stoppedAtEpochMillis = System.currentTimeMillis()
                session.exitCode = runCatching { session.process.exitValue() }.getOrNull()
            }
        }
        appendAudit(event = "stop", session = session, helperSignalSent = helperSignalSent)
        snapshot(session)
    }

    private fun drainStream(session: MutableSession, stderr: Boolean) {
        val input = if (stderr) session.process.errorStream else session.process.inputStream
        try {
            input.bufferedReader().use { reader ->
                val buffer = CharArray(READ_BUFFER_CHARS)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    val chunk = String(buffer, 0, count)
                    synchronized(lock) {
                        val target = if (stderr) session.stderr else session.stdout
                        session.outputTruncated = appendLatest(target, chunk) || session.outputTruncated
                        appendLogFile(session, chunk)
                    }
                }
            }
        } catch (exception: IOException) {
            synchronized(lock) {
                if (!session.stopRequested && session.failure == null) {
                    session.failure = exception.message ?: exception::class.java.simpleName
                }
            }
        }
    }

    private suspend fun awaitExit(session: MutableSession) = withContext(Dispatchers.IO) {
        val exitCode = runCatching { session.process.waitFor() }
            .onFailure { exception ->
                synchronized(lock) {
                    if (session.failure == null) {
                        session.failure = exception.message ?: exception::class.java.simpleName
                    }
                }
            }
            .getOrNull()
        synchronized(lock) {
            session.exitCode = exitCode
            session.stoppedAtEpochMillis = session.stoppedAtEpochMillis ?: System.currentTimeMillis()
        }
        appendAudit(event = "exit", session = session, helperSignalSent = false)
    }

    private fun appendLatest(target: StringBuilder, chunk: String): Boolean {
        target.append(chunk)
        if (target.length <= MAX_RETAINED_CHARS) return false
        target.delete(0, target.length - MAX_RETAINED_CHARS)
        return true
    }

    private fun appendLogFile(session: MutableSession, chunk: String) {
        if (session.persistedChars >= MAX_LOG_FILE_CHARS) {
            session.outputTruncated = true
            return
        }
        val remaining = MAX_LOG_FILE_CHARS - session.persistedChars
        val retained = if (chunk.length <= remaining) chunk else chunk.take(remaining)
        runCatching {
            session.logFile.parentFile?.mkdirs()
            session.logFile.appendText(retained, Charsets.UTF_8)
        }.onFailure { exception ->
            if (session.failure == null) {
                session.failure = exception.message ?: exception::class.java.simpleName
            }
        }
        session.persistedChars += retained.length
        if (retained.length < chunk.length) session.outputTruncated = true
    }

    private suspend fun appendAudit(
        event: String,
        session: MutableSession,
        helperSignalSent: Boolean,
    ) = withContext(Dispatchers.IO) {
        auditFile.parentFile?.mkdirs()
        val record = synchronized(lock) {
            JSONObject()
                .put("schemaVersion", 1)
                .put("timestampEpochMillis", System.currentTimeMillis())
                .put("event", event)
                .put("sessionId", session.sessionId)
                .put("packageName", session.packageName)
                .put("pid", session.pid)
                .put("readOnlyTarget", true)
                .put("targetStateChanged", false)
                .put("attachAttempted", false)
                .put("injectionAttempted", false)
                .put("targetSignalAttempted", false)
                .put("memoryWriteAttempted", false)
                .put("helperSignalSent", helperSignalSent)
                .put("running", session.process.isAlive)
                .put("exitCode", session.exitCode ?: JSONObject.NULL)
                .put("failure", session.failure ?: JSONObject.NULL)
                .put("outputTruncated", session.outputTruncated)
                .put("logFile", session.logFile.path)
        }
        synchronized(AUDIT_LOCK) {
            auditFile.appendText(record.toString() + "\n", Charsets.UTF_8)
        }
    }

    private fun snapshot(session: MutableSession): HostLogcatSessionSnapshot = synchronized(lock) {
        snapshotLocked(session)
    }

    private fun snapshotLocked(session: MutableSession): HostLogcatSessionSnapshot =
        HostLogcatSessionSnapshot(
            sessionId = session.sessionId,
            packageName = session.packageName,
            pid = session.pid,
            running = session.process.isAlive,
            startedAtEpochMillis = session.startedAtEpochMillis,
            stoppedAtEpochMillis = session.stoppedAtEpochMillis,
            exitCode = session.exitCode,
            stdout = session.stdout.toString(),
            stderr = session.stderr.toString(),
            outputTruncated = session.outputTruncated,
            logFile = session.logFile,
            failure = session.failure,
        )

    private data class MutableSession(
        val sessionId: String,
        val packageName: String,
        val pid: Int,
        val process: Process,
        val startedAtEpochMillis: Long,
        val logFile: File,
        val stdout: StringBuilder = StringBuilder(),
        val stderr: StringBuilder = StringBuilder(),
        var stoppedAtEpochMillis: Long? = null,
        var exitCode: Int? = null,
        var outputTruncated: Boolean = false,
        var persistedChars: Int = 0,
        var stopRequested: Boolean = false,
        var failure: String? = null,
    )

    private companion object {
        const val READ_BUFFER_CHARS = 4_096
        const val MAX_RETAINED_CHARS = 250_000
        const val MAX_LOG_FILE_CHARS = 2_000_000
        const val STOP_GRACE_MILLIS = 1_000L
        const val STOP_FORCE_MILLIS = 1_000L
        val AUDIT_LOCK = Any()
    }
}
