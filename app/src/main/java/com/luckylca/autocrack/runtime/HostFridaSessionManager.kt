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
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class HostFridaSessionSnapshot(
    val sessionId: String,
    val packageName: String,
    val pid: Int,
    val port: Int,
    val running: Boolean,
    val startedAtEpochMillis: Long,
    val stoppedAtEpochMillis: Long?,
    val helperPid: Int?,
    val helperVerified: Boolean,
    val serverReadyForClient: Boolean,
    val helperCommandLine: String?,
    val targetTracerPid: Int?,
    val operationCount: Int,
    val helperSignalSent: Boolean,
    val failure: String?,
)

data class HostFridaOperationResult(
    val operation: String,
    val succeeded: Boolean,
    val result: JSONObject?,
    val exitCode: Int?,
    val durationMillis: Long,
    val failure: String?,
)

object HostFridaAuthorization {
    fun expected(packageName: String, pid: Int): String {
        PackageOutputParser.requireValidPackageName(packageName)
        require(pid > 0) { "PID must be positive" }
        return "FRIDA $packageName $pid"
    }

    fun requireAuthorized(packageName: String, pid: Int, supplied: String) {
        val expected = expected(packageName, pid)
        require(supplied.trim() == expected) {
            "Frida 会向目标进程加载固定受控 agent；请输入精确授权短语：$expected"
        }
    }
}

sealed interface HostFridaClientOperation {
    val id: String
    val timeoutMillis: Long

    data object Ping : HostFridaClientOperation {
        override val id = "ping"
        override val timeoutMillis = 15_000L
    }

    data class Modules(val maxCount: Int = 128) : HostFridaClientOperation {
        override val id = "modules"
        override val timeoutMillis = 15_000L
    }

    data class Exports(
        val module: String,
        val query: String = "",
        val maxCount: Int = 128,
    ) : HostFridaClientOperation {
        override val id = "exports"
        override val timeoutMillis = 15_000L
    }

    data class JavaClasses(
        val query: String = "",
        val maxCount: Int = 128,
    ) : HostFridaClientOperation {
        override val id = "java_classes"
        override val timeoutMillis = 20_000L
    }

    data class JavaMethods(
        val className: String,
        val maxCount: Int = 128,
    ) : HostFridaClientOperation {
        override val id = "java_methods"
        override val timeoutMillis = 20_000L
    }

    data class NetDetectStack(val maxCount: Int = 64) : HostFridaClientOperation {
        override val id = "net_detect_stack"
        override val timeoutMillis = 20_000L
    }

    data class TlsTrace(
        val durationMillis: Int = 1_000,
        val maxEvents: Int = 64,
        val maxBytesPerEvent: Int = 256,
    ) : HostFridaClientOperation {
        override val id = "tls_trace"
        override val timeoutMillis: Long = durationMillis.coerceIn(50, 5_000) + 20_000L
    }

    data class NetworkHints(val maxCount: Int = 64) : HostFridaClientOperation {
        override val id = "network_hints"
        override val timeoutMillis = 20_000L
    }

    data class NativeTrace(
        val module: String,
        val offset: String,
        val durationMillis: Int = 1_000,
        val maxEvents: Int = 64,
    ) : HostFridaClientOperation {
        override val id = "native_trace"
        override val timeoutMillis: Long = durationMillis.coerceIn(50, 5_000) + 20_000L
    }
}

object HostFridaCommandFactory {
    fun buildStartServer(
        suPath: String,
        binaryPath: String,
        expectedBinarySha256: String,
        packageName: String,
        pid: Int,
        port: Int,
        helperPidFile: String,
    ): List<String> {
        requireSuPath(suPath)
        PackageOutputParser.requireValidPackageName(packageName)
        require(pid > 0) { "PID must be positive" }
        requireManagedPort(port)
        require(binaryPath.startsWith('/')) { "Frida binary path must be absolute" }
        require(expectedBinarySha256.matches(Regex("^[0-9a-f]{64}$"))) { "Frida binary SHA-256 must be lowercase hex" }
        require(helperPidFile.startsWith('/')) { "Helper PID file must be absolute" }
        val binary = RootToolCommandFactory.shellQuote(binaryPath)
        val expectedSha256 = RootToolCommandFactory.shellQuote(expectedBinarySha256)
        val expectedPackage = RootToolCommandFactory.shellQuote(packageName)
        val pidFile = RootToolCommandFactory.shellQuote(helperPidFile)
        val endpoint = RootToolCommandFactory.shellQuote("127.0.0.1:$port")
        val shell = """
            target_pid=$pid
            target_package=$expectedPackage
            binary=$binary
            expected_sha256=$expectedSha256
            helper_pid_file=$pidFile
            endpoint=$endpoint
            helper_port=$port
            proc=/proc/${'$'}target_pid
            [ -f "${'$'}binary" ] && [ ! -L "${'$'}binary" ] || { echo 'FRIDA_SERVER_NOT_REGULAR_FILE' >&2; exit 59; }
            [ -x "${'$'}binary" ] || { echo 'FRIDA_SERVER_NOT_EXECUTABLE' >&2; exit 60; }
            actual_sha256=${'$'}(sha256sum "${'$'}binary" 2>/dev/null | awk '{ print ${'$'}1 }')
            [ "${'$'}actual_sha256" = "${'$'}expected_sha256" ] || { echo 'FRIDA_SERVER_SHA256_MISMATCH' >&2; exit 67; }
            [ -d "${'$'}proc" ] || { echo 'FRIDA_TARGET_NOT_FOUND' >&2; exit 61; }
            argv0=${'$'}(tr '\000' '\n' < "${'$'}proc/cmdline" 2>/dev/null | head -n 1)
            case "${'$'}argv0" in
              "${'$'}target_package"|"${'$'}target_package":*) ;;
              *) echo 'FRIDA_TARGET_IDENTITY_MISMATCH' >&2; exit 62 ;;
            esac
            tracer=${'$'}(awk '/^TracerPid:/ { print ${'$'}2; exit }' "${'$'}proc/status" 2>/dev/null)
            [ "${'$'}{tracer:-0}" = "0" ] || { echo "FRIDA_TARGET_ALREADY_TRACED tracer=${'$'}tracer" >&2; exit 63; }
            printf '%s %s\n' "${'$'}${'$'}" "${'$'}helper_port" > "${'$'}helper_pid_file"
            exec "${'$'}binary" -l "${'$'}endpoint"
        """.trimIndent()
        return listOf(suPath, "-c", shell)
    }

    fun buildProbeHelper(
        suPath: String,
        binaryPath: String,
        helperPid: Int,
        port: Int,
    ): List<String> {
        requireSuPath(suPath)
        require(binaryPath.startsWith('/')) { "Frida binary path must be absolute" }
        require(helperPid > 0) { "Helper PID must be positive" }
        requireManagedPort(port)
        val binary = RootToolCommandFactory.shellQuote(binaryPath)
        val endpoint = RootToolCommandFactory.shellQuote("127.0.0.1:$port")
        val shell = """
            helper_pid=$helperPid
            binary=$binary
            endpoint=$endpoint
            proc=/proc/${'$'}helper_pid
            [ -d "${'$'}proc" ] || { echo 'FRIDA_HELPER_NOT_FOUND' >&2; exit 64; }
            argv0=${'$'}(tr '\000' '\n' < "${'$'}proc/cmdline" 2>/dev/null | head -n 1)
            [ "${'$'}argv0" = "${'$'}binary" ] || { echo 'FRIDA_HELPER_IDENTITY_MISMATCH' >&2; exit 65; }
            cmdline=${'$'}(tr '\000' ' ' < "${'$'}proc/cmdline" 2>/dev/null | sed 's/[[:space:]]*$//')
            expected_cmdline="${'$'}binary -l ${'$'}endpoint"
            [ "${'$'}cmdline" = "${'$'}expected_cmdline" ] || { echo "FRIDA_HELPER_COMMAND_MISMATCH cmdline=${'$'}cmdline" >&2; exit 66; }
            port_hex=${'$'}(printf '%04X' $port)
            listen_inode=${'$'}(awk -v endpoint="0100007F:${'$'}port_hex" '${'$'}2 == endpoint && ${'$'}4 == "0A" { print ${'$'}10; exit }' /proc/net/tcp 2>/dev/null)
            listener_ready=false
            if [ -n "${'$'}listen_inode" ]; then
              if ls -l "${'$'}proc"/fd 2>/dev/null | grep -F "socket:[${'$'}listen_inode]" >/dev/null; then
                listener_ready=true
              fi
            fi
            printf 'helper_verified=true\nlistener_ready=%s\nhelper_cmdline=%s\n' "${'$'}listener_ready" "${'$'}cmdline"
        """.trimIndent()
        return listOf(suPath, "-c", shell)
    }

    fun buildStopHelper(
        suPath: String,
        binaryPath: String,
        helperPid: Int,
        port: Int,
    ): List<String> {
        requireSuPath(suPath)
        require(binaryPath.startsWith('/')) { "Frida binary path must be absolute" }
        require(helperPid > 0) { "Helper PID must be positive" }
        requireManagedPort(port)
        val binary = RootToolCommandFactory.shellQuote(binaryPath)
        val endpoint = RootToolCommandFactory.shellQuote("127.0.0.1:$port")
        val shell = """
            helper_pid=$helperPid
            binary=$binary
            endpoint=$endpoint
            proc=/proc/${'$'}helper_pid
            [ -d "${'$'}proc" ] || exit 0
            argv0=${'$'}(tr '\000' '\n' < "${'$'}proc/cmdline" 2>/dev/null | head -n 1)
            [ "${'$'}argv0" = "${'$'}binary" ] || { echo 'FRIDA_HELPER_IDENTITY_MISMATCH' >&2; exit 65; }
            cmdline=${'$'}(tr '\000' ' ' < "${'$'}proc/cmdline" 2>/dev/null | sed 's/[[:space:]]*$//')
            expected_cmdline="${'$'}binary -l ${'$'}endpoint"
            [ "${'$'}cmdline" = "${'$'}expected_cmdline" ] || { echo "FRIDA_HELPER_COMMAND_MISMATCH cmdline=${'$'}cmdline" >&2; exit 66; }
            kill -TERM "${'$'}helper_pid"
        """.trimIndent()
        return listOf(suPath, "-c", shell)
    }

    fun buildClientCommand(
        pid: Int,
        operation: HostFridaClientOperation,
        port: Int = HostFridaSessionManager.DEFAULT_PORT,
    ): String {
        require(pid > 0) { "PID must be positive" }
        requireManagedPort(port)
        val args = mutableListOf("frida-autocrack-client", "--pid", pid.toString(), "--port", port.toString())
        when (operation) {
            HostFridaClientOperation.Ping -> args += "ping"
            is HostFridaClientOperation.Modules -> {
                args += listOf("modules", "--max-count", operation.maxCount.coerceIn(1, 512).toString())
            }
            is HostFridaClientOperation.Exports -> {
                args += listOf(
                    "exports",
                    "--module", requireBoundedText(operation.module, 256, "module"),
                    "--query", operation.query.take(512),
                    "--max-count", operation.maxCount.coerceIn(1, 512).toString(),
                )
            }
            is HostFridaClientOperation.JavaClasses -> {
                args += listOf(
                    "java-classes",
                    "--query", operation.query.take(512),
                    "--max-count", operation.maxCount.coerceIn(1, 512).toString(),
                )
            }
            is HostFridaClientOperation.JavaMethods -> {
                args += listOf(
                    "java-methods",
                    "--class-name", requireBoundedText(operation.className, 512, "className"),
                    "--max-count", operation.maxCount.coerceIn(1, 512).toString(),
                )
            }
            is HostFridaClientOperation.NetDetectStack -> {
                args += listOf(
                    "net-stack",
                    "--max-count", operation.maxCount.coerceIn(1, 128).toString(),
                )
            }
            is HostFridaClientOperation.TlsTrace -> {
                args += listOf(
                    "tls-trace",
                    "--duration-ms", operation.durationMillis.coerceIn(50, 5_000).toString(),
                    "--max-events", operation.maxEvents.coerceIn(1, 128).toString(),
                    "--max-bytes-per-event", operation.maxBytesPerEvent.coerceIn(16, 1_024).toString(),
                )
            }
            is HostFridaClientOperation.NetworkHints -> {
                args += listOf(
                    "net-hints",
                    "--max-count", operation.maxCount.coerceIn(1, 128).toString(),
                )
            }
            is HostFridaClientOperation.NativeTrace -> {
                val offset = operation.offset.trim().lowercase()
                require(Regex("0x[0-9a-f]{1,16}").matches(offset)) { "offset must be 0x-prefixed hexadecimal" }
                args += listOf(
                    "native-trace",
                    "--module", requireBoundedText(operation.module, 256, "module"),
                    "--offset", offset,
                    "--duration-ms", operation.durationMillis.coerceIn(50, 5_000).toString(),
                    "--max-events", operation.maxEvents.coerceIn(1, 128).toString(),
                )
            }
        }
        return args.joinToString(" ") { ShellEscaper.quote(it) }
    }

    private fun requireBoundedText(value: String, max: Int, label: String): String = value.trim().also {
        require(it.isNotEmpty() && it.length <= max) { "$label must be 1..$max characters" }
        require('\u0000' !in it && '\n' !in it && '\r' !in it) { "$label contains an invalid character" }
    }

    private fun requireManagedPort(port: Int) {
        require(port in HostFridaSessionManager.MIN_MANAGED_PORT..HostFridaSessionManager.MAX_MANAGED_PORT) {
            "Frida port must be in ${HostFridaSessionManager.MIN_MANAGED_PORT}..${HostFridaSessionManager.MAX_MANAGED_PORT}"
        }
    }

    private fun requireSuPath(suPath: String) {
        require(suPath.isNotBlank()) { "su path must not be blank" }
        require('\u0000' !in suPath && '\n' !in suPath && '\r' !in suPath) { "su path contains an invalid character" }
    }
}

private data class HostFridaHelperProbe(
    val helperVerified: Boolean,
    val listenerReady: Boolean,
    val helperCommandLine: String?,
)

private object HostFridaHelperProbeParser {
    fun parse(stdout: String): HostFridaHelperProbe {
        val lines = stdout.lineSequence().toList()
        val commandLine = lines.firstOrNull { it.startsWith("helper_cmdline=") }
            ?.substringAfter('=')?.takeIf(String::isNotBlank)
        val verified = commandLine != null && lines.any { it == "helper_verified=true" }
        return HostFridaHelperProbe(
            helperVerified = verified,
            listenerReady = verified && lines.any { it == "listener_ready=true" },
            helperCommandLine = commandLine,
        )
    }
}

class HostFridaSessionManager(
    context: Context,
    private val layout: RuntimeLayout,
    private val rootDetector: RootDetector,
    private val runner: RootCommandRunner = ProcessRootCommandRunner(),
) {
    val auditFile: File = File(layout.auditRoot, "dynamic-frida.jsonl")
    val sessionRoot: File = File(layout.sessionsRoot, "frida")

    private val installer = ToolpackPackageInstaller(context, layout)
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeSession: MutableFridaSession? = null

    suspend fun start(packageName: String, pid: Int, authorizationPhrase: String): HostFridaSessionSnapshot {
        PackageOutputParser.requireValidPackageName(packageName)
        require(pid > 0) { "PID 必须是正整数" }
        require(pid != AndroidProcess.myPid()) { "不能向 AutoCrackApp 自身注入 Frida agent" }
        HostFridaAuthorization.requireAuthorized(packageName, pid, authorizationPhrase)
        layout.initialize()
        check(sessionRoot.exists() || sessionRoot.mkdirs()) { "无法创建 Frida session 目录" }
        synchronized(lock) {
            require(activeSession?.process?.isAlive != true) { "已有 Frida server 会话正在运行" }
        }

        val root = rootDetector.inspect()
        require(root.isRootGranted) { root.diagnostic ?: "Frida server 需要 Root 权限" }
        val suPath = requireNotNull(root.suPath) { "Root 已授权但没有可用 su" }
        val executor = RootToolExecutor(runner, suPath)
        val installed = requireFridaToolpack()
        val binaryPath = trustedServerBinaryPath(installed)
        recoverStaleHelpers(suPath, binaryPath)
        val target = readAndValidateTarget(executor, packageName, pid)
        require(target.tracerPid == 0) { "PID $pid 已被 tracer ${target.tracerPid} 附加；拒绝启动 Frida" }

        val port = allocateLoopbackPort()
        val sessionId = UUID.randomUUID().toString()
        val helperPidFile = File(sessionRoot, "$sessionId.helper.pid").apply {
            parentFile?.mkdirs()
            writeText("", Charsets.UTF_8)
        }
        val process = withContext(Dispatchers.IO) {
            ProcessBuilder(
                HostFridaCommandFactory.buildStartServer(
                    suPath = suPath,
                    binaryPath = binaryPath,
                    expectedBinarySha256 = SERVER_BINARY_SHA256,
                    packageName = packageName,
                    pid = pid,
                    port = port,
                    helperPidFile = helperPidFile.path,
                ),
            ).redirectErrorStream(false).start()
        }
        val session = MutableFridaSession(
            sessionId = sessionId,
            packageName = packageName,
            pid = pid,
            port = port,
            binaryPath = binaryPath,
            helperPidFile = helperPidFile,
            process = process,
            suPath = suPath,
            executor = executor,
            startedAtEpochMillis = System.currentTimeMillis(),
            targetTracerPid = target.tracerPid,
        )
        synchronized(lock) { activeSession = session }
        scope.launch { drainStream(session, stderr = false) }
        scope.launch { drainStream(session, stderr = true) }
        scope.launch { awaitExit(session) }
        appendAudit("server_start", session, null)
        observeServerReady(session)
        appendAudit(if (session.serverReadyForClient) "server_ready" else "server_unready", session, null)
        require(session.serverReadyForClient) {
            session.failure ?: session.stderr.toString().takeLast(2_000).ifBlank { "Frida server 未就绪" }
        }
        return snapshot(session)
    }

    fun snapshot(): HostFridaSessionSnapshot? = synchronized(lock) { activeSession?.let(::snapshotLocked) }

    suspend fun refresh(): HostFridaSessionSnapshot? {
        val session = synchronized(lock) { activeSession } ?: return null
        readHelperPid(session)
        probeHelper(session)
        updateTargetStatus(session)
        return snapshot(session)
    }

    suspend fun execute(operation: HostFridaClientOperation): HostFridaOperationResult {
        val session = synchronized(lock) { requireNotNull(activeSession) { "当前没有 Frida session" } }
        observeServerReady(session)
        updateTargetStatus(session)
        val refreshed = snapshot(session)
        require(refreshed.running && refreshed.helperVerified && refreshed.serverReadyForClient && refreshed.failure == null) {
            "Frida helper 未通过验证或未就绪"
        }
        val before = readAndValidateTarget(session.executor, session.packageName, session.pid)
        require(before.tracerPid == 0) { "RPC 前目标 TracerPid=${before.tracerPid}；拒绝 Frida attach" }

        val hostEngine = RootShellRuntimeEngine(layout, session.suPath)
        val chroot = ChrootRuntimeEngine(layout, hostEngine)
        val command = HostFridaCommandFactory.buildClientCommand(session.pid, operation, session.port)
        val result = chroot.execute(
            ShellCommandRequest(
                command = command,
                workingDirectory = "/workspace",
                timeoutMillis = operation.timeoutMillis,
            ),
        )
        val parsed = parseClientResult(result.stdout)
        val detachVerified = waitForTargetDetached(session)
        val operationFailure = when {
            !result.succeeded -> result.failure ?: result.stderr.ifBlank { "Frida client exit=${result.exitCode}" }
            parsed == null -> "Frida client 未返回有效 JSON"
            !parsed.optBoolean("ok", false) -> parsed.optString("error").ifBlank { "Frida RPC 返回失败" }
            !detachVerified -> "Frida RPC 完成后目标 TracerPid 未恢复为 0"
            else -> null
        }
        synchronized(lock) {
            session.operationCount += 1
            if (operationFailure != null) session.failure = operationFailure
        }
        appendAudit(
            event = if (operationFailure == null) "operation_ok" else "operation_failed",
            session = session,
            operation = operation.id,
        )
        return HostFridaOperationResult(
            operation = operation.id,
            succeeded = operationFailure == null,
            result = parsed,
            exitCode = result.exitCode,
            durationMillis = result.durationMillis,
            failure = operationFailure,
        )
    }

    suspend fun stop(): HostFridaSessionSnapshot? = withContext(Dispatchers.IO) {
        val session = synchronized(lock) { activeSession } ?: return@withContext null
        readHelperPid(session)
        val helperPid = synchronized(lock) { session.helperPid }
        if (session.process.isAlive || helperPid != null) {
            require(helperPid != null && helperPid > 0) {
                "未获得 Frida helper PID，拒绝发送 signal"
            }
            val result = runner.run(
                command = HostFridaCommandFactory.buildStopHelper(
                    suPath = session.suPath,
                    binaryPath = session.binaryPath,
                    helperPid = helperPid,
                    port = session.port,
                ),
                label = "Stop AutoCrack Frida helper $helperPid",
                timeoutMillis = 3_000L,
            )
            require(result.succeeded) { result.failure ?: result.stderr.ifBlank { "Frida helper TERM 失败" } }
            synchronized(lock) { session.helperSignalSent = true }
            session.process.waitFor(3_000L, TimeUnit.MILLISECONDS)
        }
        waitForTargetDetached(session)
        synchronized(lock) {
            session.stoppedAtEpochMillis = session.stoppedAtEpochMillis ?: System.currentTimeMillis()
            session.serverReadyForClient = false
        }
        appendAudit("server_stop", session, null)
        snapshot(session)
    }

    private suspend fun requireFridaToolpack(): InstalledToolpack {
        val installed = installer.listInstalled().firstOrNull {
            it.manifest.id == TOOLPACK_ID && it.manifest.version == TOOLPACK_VERSION
        } ?: error("未安装受信任 Android Frida 工具包：$TOOLPACK_VERSION")
        BuiltInToolpackTrustPolicy.requireTrusted(installed.manifest)
        return installed
    }

    private fun trustedServerBinaryPath(installed: InstalledToolpack): String {
        val root = installed.installedPath.trimEnd('/')
        val managedRoot = layout.rootfsRoot.path.trimEnd('/') + "/"
        require(root.isNotBlank() && root.startsWith(managedRoot)) {
            "Frida toolpack 安装路径不在受管 rootfs 内"
        }
        require(!root.contains("/../") && !root.endsWith("/..")) { "Frida toolpack 路径包含非法父目录" }
        return "$root/$SERVER_RELATIVE_PATH"
    }

    private suspend fun readAndValidateTarget(
        executor: RootToolExecutor,
        packageName: String,
        pid: Int,
    ): HostDebuggerTargetStatus {
        val identity = executor.execute(RootToolCommand.ReadProcessIdentity(pid))
        require(identity.succeeded) { identity.failure ?: identity.stderr.ifBlank { "无法读取 PID $pid 身份" } }
        require(HostLogcatIdentityMatcher.matches(packageName, identity.stdout)) {
            "PID $pid 当前身份不属于包 $packageName"
        }
        return requireNotNull(HostDebuggerTargetStatusParser.parse(identity.stdout)) { "无法解析目标 TracerPid" }
    }

    private suspend fun recoverStaleHelpers(suPath: String, binaryPath: String) {
        val pidFiles = sessionRoot.listFiles()
            .orEmpty()
            .filter { file -> file.isFile && file.name.endsWith(".helper.pid") }
        var stoppedAny = false
        pidFiles.forEach { file ->
            val helper = parseHelperRecord(runCatching { file.readText(Charsets.UTF_8) }.getOrDefault(""))
            if (helper == null) {
                file.delete()
                return@forEach
            }
            val result = runner.run(
                command = HostFridaCommandFactory.buildStopHelper(
                    suPath = suPath,
                    binaryPath = binaryPath,
                    helperPid = helper.pid,
                    port = helper.port,
                ),
                label = "Recover stale AutoCrack Frida helper ${helper.pid}",
                timeoutMillis = 3_000L,
            )
            when {
                result.succeeded -> stoppedAny = true
                result.exitCode == 65 || result.exitCode == 66 -> Unit
                else -> throw IllegalStateException(
                    result.failure ?: result.stderr.ifBlank { "无法恢复 Frida helper ${helper.pid}" },
                )
            }
            file.delete()
        }
        if (stoppedAny) delay(STALE_HELPER_STOP_DELAY_MILLIS)
    }

    private fun allocateLoopbackPort(): Int = ServerSocket().use { socket ->
        socket.reuseAddress = false
        socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        socket.localPort.also { port ->
            require(port in MIN_MANAGED_PORT..MAX_MANAGED_PORT) { "系统分配了不受支持的 Frida loopback 端口：$port" }
        }
    }

    private fun parseHelperRecord(text: String): HostFridaHelperRecord? {
        val fields = text.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        val pid = fields.getOrNull(0)?.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val port = fields.getOrNull(1)?.toIntOrNull() ?: DEFAULT_PORT
        if (port !in MIN_MANAGED_PORT..MAX_MANAGED_PORT) return null
        return HostFridaHelperRecord(pid = pid, port = port)
    }

    private suspend fun waitForTargetDetached(session: MutableFridaSession): Boolean {
        repeat(DETACH_VERIFY_ATTEMPTS) {
            val status = runCatching { readAndValidateTarget(session.executor, session.packageName, session.pid) }.getOrNull()
            if (status?.tracerPid == 0) {
                synchronized(lock) { session.targetTracerPid = 0 }
                return true
            }
            if (status != null) synchronized(lock) { session.targetTracerPid = status.tracerPid }
            delay(DETACH_VERIFY_DELAY_MILLIS)
        }
        return false
    }

    private suspend fun updateTargetStatus(session: MutableFridaSession) {
        val status = runCatching { readAndValidateTarget(session.executor, session.packageName, session.pid) }
            .getOrElse { error ->
                synchronized(lock) { session.failure = error.message ?: error::class.java.simpleName }
                return
            }
        synchronized(lock) { session.targetTracerPid = status.tracerPid }
    }

    private suspend fun observeServerReady(session: MutableFridaSession) {
        repeat(SERVER_READY_ATTEMPTS) {
            readHelperPid(session)
            probeHelper(session)
            synchronized(lock) {
                if (session.serverReadyForClient || !session.process.isAlive) return
            }
            delay(SERVER_READY_DELAY_MILLIS)
        }
    }

    private suspend fun probeHelper(session: MutableFridaSession) {
        val helperPid = synchronized(lock) { session.helperPid } ?: return
        val result = runner.run(
            command = HostFridaCommandFactory.buildProbeHelper(session.suPath, session.binaryPath, helperPid, session.port),
            label = "Probe AutoCrack Frida helper $helperPid",
            timeoutMillis = 1_500L,
        )
        val parsed = if (result.succeeded) HostFridaHelperProbeParser.parse(result.stdout) else HostFridaHelperProbe(false, false, null)
        synchronized(lock) {
            session.helperVerified = parsed.helperVerified
            session.serverReadyForClient = parsed.listenerReady
            session.helperCommandLine = parsed.helperCommandLine
        }
    }

    private fun readHelperPid(session: MutableFridaSession) {
        val helper = parseHelperRecord(runCatching { session.helperPidFile.readText(Charsets.UTF_8) }.getOrDefault("")) ?: return
        synchronized(lock) {
            if (helper.port == session.port) {
                session.helperPid = helper.pid
            } else if (session.failure == null) {
                session.failure = "Frida helper PID 记录端口不匹配"
            }
        }
    }

    private fun parseClientResult(stdout: String): JSONObject? = stdout.lineSequence()
        .filter(String::isNotBlank)
        .mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }
        .lastOrNull()

    private fun drainStream(session: MutableFridaSession, stderr: Boolean) {
        val input = if (stderr) session.process.errorStream else session.process.inputStream
        try {
            input.bufferedReader().use { reader ->
                val buffer = CharArray(4_096)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    val chunk = String(buffer, 0, count)
                    synchronized(lock) {
                        val target = if (stderr) session.stderr else session.stdout
                        target.append(chunk)
                        if (target.length > MAX_RETAINED_CHARS) target.delete(0, target.length - MAX_RETAINED_CHARS)
                    }
                }
            }
        } catch (exception: IOException) {
            synchronized(lock) {
                if (session.process.isAlive && session.failure == null) session.failure = exception.message
            }
        }
    }

    private suspend fun awaitExit(session: MutableFridaSession) = withContext(Dispatchers.IO) {
        runCatching { session.process.waitFor() }.onFailure { error ->
            synchronized(lock) { if (session.failure == null) session.failure = error.message }
        }
        synchronized(lock) {
            session.stoppedAtEpochMillis = session.stoppedAtEpochMillis ?: System.currentTimeMillis()
            session.serverReadyForClient = false
        }
        appendAudit("helper_exit", session, null)
    }

    private suspend fun appendAudit(event: String, session: MutableFridaSession, operation: String?) = withContext(Dispatchers.IO) {
        auditFile.parentFile?.mkdirs()
        val record = synchronized(lock) {
            JSONObject()
                .put("schemaVersion", 1)
                .put("timestampEpochMillis", System.currentTimeMillis())
                .put("event", event)
                .put("operation", operation ?: JSONObject.NULL)
                .put("sessionId", session.sessionId)
                .put("packageName", session.packageName)
                .put("pid", session.pid)
                .put("endpoint", "127.0.0.1:${session.port}")
                .put("helperPid", session.helperPid ?: JSONObject.NULL)
                .put("helperVerified", session.helperVerified)
                .put("serverReadyForClient", session.serverReadyForClient)
                .put("targetTracerPid", session.targetTracerPid ?: JSONObject.NULL)
                .put("operationCount", session.operationCount)
                .put("helperSignalSent", session.helperSignalSent)
                .put("arbitraryScriptAllowed", false)
                .put("memoryWriteAllowed", false)
                .put("returnValueReplacementAllowed", false)
                .put("failure", session.failure ?: JSONObject.NULL)
        }
        synchronized(AUDIT_LOCK) { auditFile.appendText(record.toString() + "\n", Charsets.UTF_8) }
    }

    private fun snapshot(session: MutableFridaSession): HostFridaSessionSnapshot = synchronized(lock) { snapshotLocked(session) }

    private fun snapshotLocked(session: MutableFridaSession) = HostFridaSessionSnapshot(
        sessionId = session.sessionId,
        packageName = session.packageName,
        pid = session.pid,
        port = session.port,
        running = session.process.isAlive,
        startedAtEpochMillis = session.startedAtEpochMillis,
        stoppedAtEpochMillis = session.stoppedAtEpochMillis,
        helperPid = session.helperPid,
        helperVerified = session.helperVerified,
        serverReadyForClient = session.serverReadyForClient,
        helperCommandLine = session.helperCommandLine,
        targetTracerPid = session.targetTracerPid,
        operationCount = session.operationCount,
        helperSignalSent = session.helperSignalSent,
        failure = session.failure,
    )

    private data class HostFridaHelperRecord(
        val pid: Int,
        val port: Int,
    )

    private data class MutableFridaSession(
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
        val stdout: StringBuilder = StringBuilder(),
        val stderr: StringBuilder = StringBuilder(),
        var stoppedAtEpochMillis: Long? = null,
        var helperPid: Int? = null,
        var helperVerified: Boolean = false,
        var serverReadyForClient: Boolean = false,
        var helperCommandLine: String? = null,
        var targetTracerPid: Int? = 0,
        var operationCount: Int = 0,
        var helperSignalSent: Boolean = false,
        var failure: String? = null,
    )

    companion object {
        const val TOOLPACK_ID = "android-frida"
        const val TOOLPACK_VERSION = "frida-17.17.0-autocrack-1.0.3"
        const val DEFAULT_PORT = 27042
        const val MIN_MANAGED_PORT = 10_240
        const val MAX_MANAGED_PORT = 65_535
        private const val SERVER_RELATIVE_PATH = "bin/frida-server-android"
        private const val SERVER_BINARY_SHA256 = "55ef78c3f3e7a55122ca7e0051e2a356d0ff1d9744d84c1660291f90400588e7"
        private const val SERVER_READY_ATTEMPTS = 40
        private const val SERVER_READY_DELAY_MILLIS = 100L
        private const val DETACH_VERIFY_ATTEMPTS = 30
        private const val DETACH_VERIFY_DELAY_MILLIS = 100L
        private const val STALE_HELPER_STOP_DELAY_MILLIS = 250L
        private const val MAX_RETAINED_CHARS = 100_000
        private val AUDIT_LOCK = Any()
    }
}
