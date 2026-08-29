package com.luckylca.autocrack.agent

import com.luckylca.autocrack.runtime.HostExecutionIdentity
import com.luckylca.autocrack.runtime.RuntimeEngine
import com.luckylca.autocrack.runtime.ShellCommandRequest
import com.luckylca.autocrack.runtime.ShellEscaper
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Session-scoped loopback bridge used by the android-host-shell toolpack.
 *
 * The Debian client never receives a generic model-facing host tool. It connects to this bridge
 * over 127.0.0.1 using a random per-session token. The bridge then executes the requested argv
 * through the existing RootShellRuntimeEngine, preserving root execution, timeout and audit.
 */
class AndroidHostShellBridge(
    private val host: RuntimeEngine,
    private val sessionId: String,
    private val hostWorkspacePath: String,
    private val dangerousOperationGate: (suspend (DangerousOperationRequest) -> DangerousOperationDecision)? = null,
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server = ServerSocket()
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val closed = AtomicBoolean(false)
    private val token = ByteArray(TOKEN_BYTES).also(SecureRandom()::nextBytes)
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    @Volatile
    private var boundPort: Int = -1

    fun start(): AndroidHostShellBridge {
        check(boundPort < 0) { "Android host shell bridge 已启动" }
        server.reuseAddress = false
        server.bind(InetSocketAddress(InetAddress.getByName(LOOPBACK_HOST), 0), SERVER_BACKLOG)
        boundPort = server.localPort
        scope.launch { acceptLoop() }
        return this
    }

    fun environment(): Map<String, String> {
        check(boundPort > 0 && !closed.get()) { "Android host shell bridge 尚未启动或已关闭" }
        return mapOf(
            ENV_HOST to LOOPBACK_HOST,
            ENV_PORT to boundPort.toString(),
            ENV_TOKEN to token,
            ENV_HOST_WORKSPACE to hostWorkspacePath,
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server.close() }
        activeSockets.toList().forEach { socket -> runCatching { socket.close() } }
        activeSockets.clear()
        scope.cancel()
    }

    private fun acceptLoop() {
        while (!closed.get()) {
            val socket = try {
                server.accept()
            } catch (_: SocketException) {
                if (closed.get()) return
                continue
            }
            activeSockets += socket
            scope.launch {
                try {
                    handle(socket)
                } finally {
                    activeSockets -= socket
                    runCatching { socket.close() }
                }
            }
        }
    }

    private suspend fun handle(socket: Socket) {
        socket.soTimeout = REQUEST_READ_TIMEOUT_MILLIS
        val response = runCatching {
            require(socket.inetAddress.isLoopbackAddress) { "仅允许本机 loopback 客户端" }
            val line = socket.getInputStream().readBoundedLine(MAX_REQUEST_BYTES)
            require(line.isNotBlank()) { "bridge 请求不能为空" }
            executeRequest(JSONObject(line))
        }.getOrElse { error ->
            errorResponse(error.message ?: error::class.java.simpleName)
        }
        val bytes = (response.toString() + "\n").toByteArray(Charsets.UTF_8)
        socket.getOutputStream().use { output ->
            output.write(bytes)
            output.flush()
        }
    }

    private suspend fun executeRequest(request: JSONObject): JSONObject {
        val suppliedToken = request.optString("token")
        require(constantTimeEquals(token, suppliedToken)) { "bridge token 无效" }
        val argvJson = request.optJSONArray("argv") ?: error("缺少 argv")
        require(argvJson.length() in 1..MAX_ARG_COUNT) { "argv 数量非法" }
        val argv = buildList(argvJson.length()) {
            var totalChars = 0
            for (index in 0 until argvJson.length()) {
                val value = argvJson.getString(index)
                require(value.length <= MAX_ARG_CHARS) { "argv[$index] 过长" }
                require('\u0000' !in value) { "argv[$index] 包含 NUL" }
                totalChars += value.length
                require(totalChars <= MAX_TOTAL_ARG_CHARS) { "argv 总长度过大" }
                add(value)
            }
        }
        val timeoutMillis = request.optLong("timeout_ms", ShellCommandRequest.DEFAULT_TIMEOUT_MILLIS)
        require(timeoutMillis in ShellCommandRequest.MIN_TIMEOUT_MILLIS..ShellCommandRequest.MAX_TIMEOUT_MILLIS) {
            "timeout_ms 超出允许范围"
        }
        val stdin = request.optString("stdin").takeIf { request.has("stdin") && !request.isNull("stdin") }
        require(stdin == null || stdin.length <= MAX_STDIN_CHARS) { "stdin 过大" }
        val reason = request.optString("reason").takeIf { request.has("reason") && !request.isNull("reason") }
            ?.take(MAX_REASON_CHARS)

        val policyCommand = commandForPolicy(argv)
        val dangerousCategory = MobileAgentDangerousCommandClassifier.classify(policyCommand)
        if (dangerousCategory != null && dangerousOperationGate != null) {
            val decision = dangerousOperationGate.invoke(
                DangerousOperationRequest(
                    conversationId = sessionId,
                    category = dangerousCategory,
                    command = policyCommand.take(MAX_POLICY_COMMAND_CHARS),
                    reason = reason,
                ),
            )
            if (decision == DangerousOperationDecision.DENY) {
                return JSONObject()
                    .put("ok", false)
                    .put("exitCode", DENIED_EXIT_CODE)
                    .put("timedOut", false)
                    .put("cancelled", false)
                    .put("failure", "用户拒绝了危险 Android host 操作")
                    .put("stdout", "")
                    .put("stderr", "")
                    .put("dangerousCategory", dangerousCategory.name)
            }
        }

        val command = argv.joinToString(" ", transform = ShellEscaper::quote)
        val result = host.execute(
            ShellCommandRequest(
                command = command,
                workingDirectory = hostWorkspacePath,
                stdin = stdin,
                timeoutMillis = timeoutMillis,
                identity = HostExecutionIdentity.ROOT,
            ),
        )
        return JSONObject()
            .put("ok", result.succeeded)
            .put("exitCode", result.exitCode ?: JSONObject.NULL)
            .put("timedOut", result.timedOut)
            .put("cancelled", result.cancelled)
            .put("failure", result.failure ?: JSONObject.NULL)
            .put("stdout", result.stdout)
            .put("stderr", result.stderr)
            .put("stdoutTruncated", result.stdoutTruncated)
            .put("stderrTruncated", result.stderrTruncated)
            .put("durationMillis", result.durationMillis)
            .put("auditFile", result.auditFilePath)
    }

    private fun errorResponse(message: String): JSONObject = JSONObject()
        .put("ok", false)
        .put("exitCode", BRIDGE_ERROR_EXIT_CODE)
        .put("timedOut", false)
        .put("cancelled", false)
        .put("failure", message.take(MAX_ERROR_CHARS))
        .put("stdout", "")
        .put("stderr", "")

    companion object {
        const val TOOLPACK_ID = "android-host-shell"
        const val TOOLPACK_VERSION = "android-host-shell-1.0.1"
        const val COMMAND_NAME = "android-shell"
        const val ENV_HOST = "AUTOC_ANDROID_HOST_ADDR"
        const val ENV_PORT = "AUTOC_ANDROID_HOST_PORT"
        const val ENV_TOKEN = "AUTOC_ANDROID_HOST_TOKEN"
        const val ENV_HOST_WORKSPACE = "AUTOC_ANDROID_HOST_WORKSPACE"

        internal fun commandForPolicy(argv: List<String>): String = when {
            argv.size >= 3 && argv[0] in setOf("sh", "/system/bin/sh") && argv[1] == "-c" -> argv[2]
            else -> argv.joinToString(" ")
        }

        private const val LOOPBACK_HOST = "127.0.0.1"
        private const val TOKEN_BYTES = 32
        private const val SERVER_BACKLOG = 8
        private const val REQUEST_READ_TIMEOUT_MILLIS = 5_000
        private const val MAX_REQUEST_BYTES = 1_200_000
        private const val MAX_ARG_COUNT = 256
        private const val MAX_ARG_CHARS = 16_384
        private const val MAX_TOTAL_ARG_CHARS = 200_000
        private const val MAX_STDIN_CHARS = 1_000_000
        private const val MAX_REASON_CHARS = 1_000
        private const val MAX_POLICY_COMMAND_CHARS = 20_000
        private const val MAX_ERROR_CHARS = 2_000
        private const val DENIED_EXIT_CODE = 126
        private const val BRIDGE_ERROR_EXIT_CODE = 125

        private fun constantTimeEquals(expected: String, actual: String): Boolean =
            MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), actual.toByteArray(Charsets.UTF_8))
    }
}

private fun InputStream.readBoundedLine(maxBytes: Int): String {
    val output = ByteArrayOutputStream()
    while (true) {
        val value = read()
        if (value < 0 || value == '\n'.code) break
        require(output.size() < maxBytes) { "bridge 请求过大" }
        output.write(value)
    }
    return output.toString(Charsets.UTF_8.name())
}
