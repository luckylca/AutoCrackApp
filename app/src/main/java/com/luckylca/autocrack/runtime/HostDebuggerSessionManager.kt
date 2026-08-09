package com.luckylca.autocrack.runtime

import android.content.Context
import android.os.Process as AndroidProcess
import com.luckylca.autocrack.apk.PackageOutputParser
import com.luckylca.autocrack.root.ProcessRootCommandRunner
import com.luckylca.autocrack.root.RootCommandRunner
import com.luckylca.autocrack.root.RootDetector
import com.luckylca.autocrack.root.RootToolCommand
import com.luckylca.autocrack.root.RootToolCommandFactory
import com.luckylca.autocrack.root.RootToolExecutor
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class HostDebuggerSessionSnapshot(
    val sessionId: String,
    val packageName: String,
    val pid: Int,
    val port: Int,
    val running: Boolean,
    val startedAtEpochMillis: Long,
    val stoppedAtEpochMillis: Long?,
    val exitCode: Int?,
    val helperPid: Int?,
    val explicitAuthorizationVerified: Boolean,
    val attachAttempted: Boolean,
    val attachedObserved: Boolean,
    val tracerPidBefore: Int,
    val tracerPidCurrent: Int?,
    val targetStateBefore: String?,
    val targetStateCurrent: String?,
    val targetStateChanged: Boolean,
    val detachVerified: Boolean,
    val targetSignalAttempted: Boolean,
    val helperSignalSent: Boolean,
    val autoCrackClientConnected: Boolean,
    val memoryCommandSent: Boolean,
    val registerWriteCommandSent: Boolean,
    val breakpointCommandSent: Boolean,
    val stdout: String,
    val stderr: String,
    val outputTruncated: Boolean,
    val failure: String?,
)

object HostDebuggerAuthorization {
    fun expected(packageName: String, pid: Int): String {
        PackageOutputParser.requireValidPackageName(packageName)
        require(pid > 0) { "PID must be positive" }
        return "ATTACH $packageName $pid"
    }

    fun requireAuthorized(packageName: String, pid: Int, supplied: String) {
        val expected = expected(packageName, pid)
        require(supplied.trim() == expected) {
            "调试器附加会暂停目标进程；请输入精确授权短语：$expected"
        }
    }
}

data class HostDebuggerTargetStatus(
    val tracerPid: Int,
    val state: String?,
)

object HostDebuggerTargetStatusParser {
    fun parse(identityOutput: String): HostDebuggerTargetStatus? {
        val tracerPid = identityOutput
            .lineSequence()
            .firstOrNull { line -> line.startsWith("TracerPid:") }
            ?.substringAfter(':')
            ?.trim()
            ?.toIntOrNull()
            ?: return null
        val state = identityOutput
            .lineSequence()
            .firstOrNull { line -> line.startsWith("State:") }
            ?.substringAfter(':')
            ?.trim()
            ?.takeIf(String::isNotBlank)
        return HostDebuggerTargetStatus(tracerPid = tracerPid, state = state)
    }
}

object HostDebuggerCommandFactory {
    fun buildAttach(
        suPath: String,
        binaryPath: String,
        packageName: String,
        pid: Int,
        port: Int,
        helperPidFile: String,
    ): List<String> {
        requireSuPath(suPath)
        PackageOutputParser.requireValidPackageName(packageName)
        require(pid > 0) { "PID must be positive" }
        require(port in MIN_PORT..MAX_PORT) { "Debugger port must be $MIN_PORT..$MAX_PORT" }
        require(binaryPath.startsWith('/')) { "Debugger binary path must be absolute" }
        require(helperPidFile.startsWith('/')) { "Helper PID file must be absolute" }

        val binary = RootToolCommandFactory.shellQuote(binaryPath)
        val expectedPackage = RootToolCommandFactory.shellQuote(packageName)
        val pidFile = RootToolCommandFactory.shellQuote(helperPidFile)
        val shell = """
            target_pid=$pid
            target_package=$expectedPackage
            binary=$binary
            helper_pid_file=$pidFile
            proc=/proc/${'$'}target_pid
            [ -d "${'$'}proc" ] || { echo 'DEBUG_TARGET_NOT_FOUND pid=$pid' >&2; exit 41; }
            argv0=${'$'}(tr '\000' '\n' < "${'$'}proc/cmdline" 2>/dev/null | head -n 1)
            case "${'$'}argv0" in
              "${'$'}target_package"|"${'$'}target_package":*) ;;
              *) echo "DEBUG_TARGET_IDENTITY_MISMATCH pid=${'$'}target_pid argv0=${'$'}argv0" >&2; exit 42 ;;
            esac
            tracer=${'$'}(awk '/^TracerPid:/ { print ${'$'}2; exit }' "${'$'}proc/status" 2>/dev/null)
            [ "${'$'}{tracer:-0}" = "0" ] || { echo "DEBUG_TARGET_ALREADY_TRACED tracer=${'$'}tracer" >&2; exit 43; }
            printf '%s\n' "${'$'}${'$'}" > "${'$'}helper_pid_file"
            exec "${'$'}binary" gdbserver "127.0.0.1:$port" --attach "${'$'}target_pid"
        """.trimIndent()
        return listOf(suPath, "-c", shell)
    }

    fun buildStopHelper(
        suPath: String,
        binaryPath: String,
        helperPid: Int,
    ): List<String> {
        requireSuPath(suPath)
        require(binaryPath.startsWith('/')) { "Debugger binary path must be absolute" }
        require(helperPid > 0) { "Helper PID must be positive" }
        val binary = RootToolCommandFactory.shellQuote(binaryPath)
        val shell = """
            helper_pid=$helperPid
            binary=$binary
            proc=/proc/${'$'}helper_pid
            [ -d "${'$'}proc" ] || exit 0
            argv0=${'$'}(tr '\000' '\n' < "${'$'}proc/cmdline" 2>/dev/null | head -n 1)
            [ "${'$'}argv0" = "${'$'}binary" ] || {
              echo "DEBUG_HELPER_IDENTITY_MISMATCH pid=${'$'}helper_pid argv0=${'$'}argv0" >&2
              exit 44
            }
            kill -TERM "${'$'}helper_pid"
        """.trimIndent()
        return listOf(suPath, "-c", shell)
    }

    private fun requireSuPath(suPath: String) {
        require(suPath.isNotBlank()) { "su path must not be blank" }
        require('\u0000' !in suPath && '\n' !in suPath && '\r' !in suPath) {
            "su path contains an invalid character"
        }
    }

    private const val MIN_PORT = 1024
    private const val MAX_PORT = 65535
}

class HostDebuggerSessionManager(
    context: Context,
    private val layout: RuntimeLayout,
    private val rootDetector: RootDetector,
    private val runner: RootCommandRunner = ProcessRootCommandRunner(),
) {
    val auditFile: File = File(layout.auditRoot, "dynamic-debugger.jsonl")
    val sessionRoot: File = File(layout.sessionsRoot, "debugger")

    private val installer = ToolpackPackageInstaller(context, layout)
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeSession: MutableDebuggerSession? = null

    suspend fun start(
        packageName: String,
        pid: Int,
        port: Int,
        authorizationPhrase: String,
    ): HostDebuggerSessionSnapshot {
        PackageOutputParser.requireValidPackageName(packageName)
        require(pid > 0) { "PID 必须是正整数" }
        require(pid != AndroidProcess.myPid()) {
            "不能附加 AutoCrackApp 自身控制进程；请选择另一款自有或明确授权的测试应用"
        }
        HostDebuggerAuthorization.requireAuthorized(packageName, pid, authorizationPhrase)
        layout.initialize()
        check(sessionRoot.exists() || sessionRoot.mkdirs()) { "无法创建 debugger session 目录" }

        synchronized(lock) {
            require(activeSession?.process?.isAlive != true) {
                "已有 LLDB server 会话正在运行，请先安全 detach"
            }
        }

        val rootStatus = rootDetector.inspect()
        require(rootStatus.isRootGranted) {
            rootStatus.diagnostic ?: "LLDB server attach 需要 Root 权限"
        }
        val suPath = requireNotNull(rootStatus.suPath) { "Root 已授权但没有可用的 su 路径" }
        val executor = RootToolExecutor(runner, suPath)
        val installed = requireDebuggerToolpack()
        val binary = requireTrustedBinary(installed)

        val identity = executor.execute(RootToolCommand.ReadProcessIdentity(pid))
        require(identity.succeeded) {
            identity.failure ?: identity.stderr.ifBlank { "无法读取 PID $pid 身份" }
        }
        require(HostLogcatIdentityMatcher.matches(packageName, identity.stdout)) {
            "PID $pid 当前身份不属于包 $packageName；已拒绝 attach"
        }
        val before = requireNotNull(HostDebuggerTargetStatusParser.parse(identity.stdout)) {
            "无法解析 PID $pid 的 TracerPid"
        }
        require(before.tracerPid == 0) {
            "PID $pid 已被 tracer ${before.tracerPid} 附加；拒绝重复 attach"
        }

        val preflight = executor.execute(RootToolCommand.ReadProcessAttachPreflight(pid))
        require(preflight.succeeded) {
            preflight.failure ?: preflight.stderr.ifBlank { "PID $pid attach 前置检查失败" }
        }

        val sessionId = UUID.randomUUID().toString()
        val helperPidFile = File(sessionRoot, "$sessionId.helper.pid")
        helperPidFile.delete()
        helperPidFile.parentFile?.mkdirs()
        helperPidFile.writeText("", Charsets.UTF_8)
        val process = withContext(Dispatchers.IO) {
            ProcessBuilder(
                HostDebuggerCommandFactory.buildAttach(
                    suPath = suPath,
                    binaryPath = binary.path,
                    packageName = packageName,
                    pid = pid,
                    port = port,
                    helperPidFile = helperPidFile.path,
                ),
            )
                .redirectErrorStream(false)
                .start()
        }
        val session = MutableDebuggerSession(
            sessionId = sessionId,
            packageName = packageName,
            pid = pid,
            port = port,
            binaryPath = binary.path,
            helperPidFile = helperPidFile,
            process = process,
            suPath = suPath,
            executor = executor,
            startedAtEpochMillis = System.currentTimeMillis(),
            tracerPidBefore = before.tracerPid,
            targetStateBefore = before.state,
        )
        synchronized(lock) { activeSession = session }
        appendAudit("start", session)

        scope.launch { drainStream(session, stderr = false) }
        scope.launch { drainStream(session, stderr = true) }
        scope.launch { awaitExit(session) }

        observeAttach(session)
        appendAudit(if (session.attachedObserved) "attached" else "attach_unconfirmed", session)
        return snapshot(session)
    }

    fun snapshot(): HostDebuggerSessionSnapshot? = synchronized(lock) {
        activeSession?.let(::snapshotLocked)
    }

    suspend fun refresh(): HostDebuggerSessionSnapshot? {
        val session = synchronized(lock) { activeSession } ?: return null
        readHelperPid(session)
        updateTargetStatus(session)
        return snapshot(session)
    }

    suspend fun stop(): HostDebuggerSessionSnapshot? = withContext(Dispatchers.IO) {
        val session = synchronized(lock) { activeSession } ?: return@withContext null
        readHelperPid(session)
        val helperPid = synchronized(lock) { session.helperPid }
        if (session.process.isAlive) {
            require(helperPid != null && helperPid > 0) {
                "未获得可信 LLDB helper PID，拒绝发送任何 signal；请保留诊断"
            }
            val stopResult = runner.run(
                command = HostDebuggerCommandFactory.buildStopHelper(
                    suPath = session.suPath,
                    binaryPath = session.binaryPath,
                    helperPid = helperPid,
                ),
                label = "Stop AutoCrack LLDB helper $helperPid",
                timeoutMillis = HELPER_STOP_COMMAND_TIMEOUT_MILLIS,
            )
            require(stopResult.succeeded) {
                stopResult.failure ?: stopResult.stderr.ifBlank { "LLDB helper TERM 失败" }
            }
            synchronized(lock) { session.helperSignalSent = true }
            session.process.waitFor(HELPER_STOP_WAIT_MILLIS, TimeUnit.MILLISECONDS)
        }

        verifyDetach(session)
        synchronized(lock) {
            session.stoppedAtEpochMillis = session.stoppedAtEpochMillis ?: System.currentTimeMillis()
            session.exitCode = runCatching { session.process.exitValue() }.getOrNull()
        }
        appendAudit(if (session.detachVerified) "detach_verified" else "detach_unverified", session)
        snapshot(session)
    }

    private suspend fun requireDebuggerToolpack(): InstalledToolpack {
        val installed = installer.listInstalled()
            .firstOrNull { toolpack ->
                toolpack.manifest.id == TOOLPACK_ID && toolpack.manifest.version == TOOLPACK_VERSION
            }
            ?: error(
                "未安装受信任 Android LLDB server 工具包：$TOOLPACK_VERSION；请先在工具包页面安装并完成自检",
            )
        BuiltInToolpackTrustPolicy.requireTrusted(installed.manifest)
        return installed
    }

    private fun requireTrustedBinary(installed: InstalledToolpack): File {
        val root = File(installed.installedPath).canonicalFile
        val binary = File(root, LLDB_SERVER_RELATIVE_PATH).canonicalFile
        require(binary.path.startsWith(root.path + File.separator)) { "LLDB server 路径越界" }
        require(binary.isFile) { "LLDB server 二进制不存在：${binary.path}" }
        require(binary.canExecute()) { "LLDB server 没有执行权限：${binary.path}" }
        val expected = installed.manifest.sources
            .firstOrNull { source -> source.name == "lldb-server" }
            ?.sha256
            ?: error("LLDB server manifest 缺少 source SHA-256")
        val actual = sha256(binary)
        require(actual == expected) {
            "LLDB server 二进制 SHA-256 不匹配：expected=$expected actual=$actual"
        }
        return binary
    }

    private suspend fun observeAttach(session: MutableDebuggerSession) {
        repeat(ATTACH_OBSERVE_ATTEMPTS) {
            readHelperPid(session)
            updateTargetStatus(session)
            synchronized(lock) {
                if (session.attachedObserved) return
                if (!session.process.isAlive) return
            }
            delay(ATTACH_OBSERVE_DELAY_MILLIS)
        }
    }

    private suspend fun verifyDetach(session: MutableDebuggerSession) {
        repeat(DETACH_VERIFY_ATTEMPTS) {
            updateTargetStatus(session)
            synchronized(lock) {
                if (session.tracerPidCurrent == 0) {
                    session.detachVerified = true
                    return
                }
            }
            delay(DETACH_VERIFY_DELAY_MILLIS)
        }
    }

    private suspend fun updateTargetStatus(session: MutableDebuggerSession) {
        val identity = session.executor.execute(RootToolCommand.ReadProcessIdentity(session.pid))
        if (!identity.succeeded) {
            synchronized(lock) {
                if (session.failure == null) {
                    session.failure = identity.failure ?: identity.stderr.ifBlank {
                        "无法刷新目标 PID ${session.pid} 状态"
                    }
                }
            }
            return
        }
        if (!HostLogcatIdentityMatcher.matches(session.packageName, identity.stdout)) {
            synchronized(lock) {
                session.failure = "PID ${session.pid} 身份已不再属于包 ${session.packageName}；拒绝继续调试会话"
            }
            return
        }
        val parsed = HostDebuggerTargetStatusParser.parse(identity.stdout) ?: return
        synchronized(lock) {
            session.tracerPidCurrent = parsed.tracerPid
            session.targetStateCurrent = parsed.state
            if (parsed.tracerPid != session.tracerPidBefore) session.targetStateChanged = true
            if (parsed.tracerPid > 0) {
                val helperPid = session.helperPid
                if (helperPid == null) {
                    session.failure = "观察到 TracerPid=${parsed.tracerPid}，但尚未获得可信 LLDB helper PID"
                } else if (parsed.tracerPid != helperPid) {
                    session.failure =
                        "目标 TracerPid=${parsed.tracerPid} 与本会话 LLDB helperPid=$helperPid 不一致；拒绝确认 attach"
                } else {
                    session.attachedObserved = true
                    session.targetStateChanged = true
                    session.failure = null
                }
            }
        }
    }

    private fun readHelperPid(session: MutableDebuggerSession) {
        val parsed = runCatching {
            session.helperPidFile
                .takeIf(File::isFile)
                ?.readText(Charsets.UTF_8)
                ?.trim()
                ?.toIntOrNull()
        }.getOrNull()
        if (parsed != null && parsed > 0) {
            synchronized(lock) { session.helperPid = parsed }
        }
    }

    private fun drainStream(session: MutableDebuggerSession, stderr: Boolean) {
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
                        target.append(chunk)
                        if (target.length > MAX_RETAINED_CHARS) {
                            target.delete(0, target.length - MAX_RETAINED_CHARS)
                            session.outputTruncated = true
                        }
                    }
                }
            }
        } catch (exception: IOException) {
            synchronized(lock) {
                if (session.failure == null && session.process.isAlive) {
                    session.failure = exception.message ?: exception::class.java.simpleName
                }
            }
        }
    }

    private suspend fun awaitExit(session: MutableDebuggerSession) = withContext(Dispatchers.IO) {
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
        appendAudit("helper_exit", session)
    }

    private suspend fun appendAudit(event: String, session: MutableDebuggerSession) =
        withContext(Dispatchers.IO) {
            auditFile.parentFile?.mkdirs()
            val record = synchronized(lock) {
                JSONObject()
                    .put("schemaVersion", 1)
                    .put("timestampEpochMillis", System.currentTimeMillis())
                    .put("event", event)
                    .put("sessionId", session.sessionId)
                    .put("packageName", session.packageName)
                    .put("pid", session.pid)
                    .put("loopbackAddress", "127.0.0.1")
                    .put("port", session.port)
                    .put("explicitAuthorizationVerified", true)
                    .put("attachAttempted", true)
                    .put("attachedObserved", session.attachedObserved)
                    .put("targetStateChanged", session.targetStateChanged)
                    .put("tracerPidBefore", session.tracerPidBefore)
                    .put("tracerPidCurrent", session.tracerPidCurrent ?: JSONObject.NULL)
                    .put("targetStateBefore", session.targetStateBefore ?: JSONObject.NULL)
                    .put("targetStateCurrent", session.targetStateCurrent ?: JSONObject.NULL)
                    .put("detachVerified", session.detachVerified)
                    .put("targetSignalAttempted", false)
                    .put("helperSignalSent", session.helperSignalSent)
                    .put("autoCrackClientConnected", false)
                    .put("memoryCommandSent", false)
                    .put("registerWriteCommandSent", false)
                    .put("breakpointCommandSent", false)
                    .put("running", session.process.isAlive)
                    .put("helperPid", session.helperPid ?: JSONObject.NULL)
                    .put("exitCode", session.exitCode ?: JSONObject.NULL)
                    .put("failure", session.failure ?: JSONObject.NULL)
            }
            synchronized(AUDIT_LOCK) {
                auditFile.appendText(record.toString() + "\n", Charsets.UTF_8)
            }
        }

    private fun snapshot(session: MutableDebuggerSession): HostDebuggerSessionSnapshot = synchronized(lock) {
        snapshotLocked(session)
    }

    private fun snapshotLocked(session: MutableDebuggerSession) = HostDebuggerSessionSnapshot(
        sessionId = session.sessionId,
        packageName = session.packageName,
        pid = session.pid,
        port = session.port,
        running = session.process.isAlive,
        startedAtEpochMillis = session.startedAtEpochMillis,
        stoppedAtEpochMillis = session.stoppedAtEpochMillis,
        exitCode = session.exitCode,
        helperPid = session.helperPid,
        explicitAuthorizationVerified = true,
        attachAttempted = true,
        attachedObserved = session.attachedObserved,
        tracerPidBefore = session.tracerPidBefore,
        tracerPidCurrent = session.tracerPidCurrent,
        targetStateBefore = session.targetStateBefore,
        targetStateCurrent = session.targetStateCurrent,
        targetStateChanged = session.targetStateChanged,
        detachVerified = session.detachVerified,
        targetSignalAttempted = false,
        helperSignalSent = session.helperSignalSent,
        autoCrackClientConnected = false,
        memoryCommandSent = false,
        registerWriteCommandSent = false,
        breakpointCommandSent = false,
        stdout = session.stdout.toString(),
        stderr = session.stderr.toString(),
        outputTruncated = session.outputTruncated,
        failure = session.failure,
    )

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private data class MutableDebuggerSession(
        val sessionId: String,
        val packageName: String,
        val pid: Int,
        val port: Int,
        val binaryPath: String,
        val helperPidFile: File,
        val process: Process,
        val suPath: String,
        val executor: RootToolExecutor,
        val startedAtEpochMillis: Long,
        val tracerPidBefore: Int,
        val targetStateBefore: String?,
        val stdout: StringBuilder = StringBuilder(),
        val stderr: StringBuilder = StringBuilder(),
        var stoppedAtEpochMillis: Long? = null,
        var exitCode: Int? = null,
        var helperPid: Int? = null,
        var attachedObserved: Boolean = false,
        var tracerPidCurrent: Int? = null,
        var targetStateCurrent: String? = null,
        var targetStateChanged: Boolean = false,
        var detachVerified: Boolean = false,
        var helperSignalSent: Boolean = false,
        var outputTruncated: Boolean = false,
        var failure: String? = null,
    )

    companion object {
        const val TOOLPACK_ID = "android-lldb-server"
        const val TOOLPACK_VERSION = "ndk-r27d-clang-r522817d_autocrack-1.0.0"
        const val DEFAULT_PORT = 5039
        private const val LLDB_SERVER_RELATIVE_PATH = "bin/lldb-server-android"
        private const val READ_BUFFER_CHARS = 4_096
        private const val MAX_RETAINED_CHARS = 200_000
        private const val ATTACH_OBSERVE_ATTEMPTS = 20
        private const val ATTACH_OBSERVE_DELAY_MILLIS = 150L
        private const val DETACH_VERIFY_ATTEMPTS = 25
        private const val DETACH_VERIFY_DELAY_MILLIS = 120L
        private const val HELPER_STOP_COMMAND_TIMEOUT_MILLIS = 3_000L
        private const val HELPER_STOP_WAIT_MILLIS = 3_000L
        private val AUDIT_LOCK = Any()
    }
}
