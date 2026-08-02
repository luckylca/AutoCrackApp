package com.luckylca.autocrack.runtime

enum class RuntimeCapabilityMode {
    FULL_ROOT,
    SHIZUKU,
    UNAVAILABLE,
}

enum class RuntimeRootfsState {
    NOT_INSTALLED,
    MANIFEST_READY,
    INSTALLING,
    INSTALLED,
    BROKEN,
}

enum class HostExecutionIdentity {
    ROOT,
    APP,
}

data class ShellCommandRequest(
    val command: String,
    val workingDirectory: String,
    val environment: Map<String, String> = emptyMap(),
    val stdin: String? = null,
    val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    val identity: HostExecutionIdentity = HostExecutionIdentity.ROOT,
) {
    init {
        require(command.isNotBlank()) { "命令不能为空" }
        require(workingDirectory.isNotBlank()) { "工作目录不能为空" }
        require(timeoutMillis in MIN_TIMEOUT_MILLIS..MAX_TIMEOUT_MILLIS) {
            "超时必须位于 $MIN_TIMEOUT_MILLIS..$MAX_TIMEOUT_MILLIS ms"
        }
        environment.keys.forEach { key ->
            require(ENV_NAME_REGEX.matches(key)) { "非法环境变量名：$key" }
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 30_000L
        const val MIN_TIMEOUT_MILLIS = 100L
        const val MAX_TIMEOUT_MILLIS = 30 * 60 * 1_000L
        private val ENV_NAME_REGEX = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}

data class ShellCommandResult(
    val requestId: String,
    val command: String,
    val workingDirectory: String,
    val identity: HostExecutionIdentity,
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val startedAtEpochMillis: Long,
    val completedAtEpochMillis: Long,
    val timedOut: Boolean,
    val cancelled: Boolean,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean,
    val failure: String?,
    val auditFilePath: String,
) {
    val durationMillis: Long
        get() = (completedAtEpochMillis - startedAtEpochMillis).coerceAtLeast(0L)

    val succeeded: Boolean
        get() = exitCode == 0 && !timedOut && !cancelled && failure == null
}

data class RuntimeHealthReport(
    val capabilityMode: RuntimeCapabilityMode,
    val rootfsState: RuntimeRootfsState,
    val runtimeRoot: String,
    val workspaceRoot: String,
    val rootIdentity: String?,
    val architecture: String?,
    val selinuxContext: String?,
    val shellPath: String?,
    val chrootPath: String?,
    val mountPath: String?,
    val tarPath: String?,
    val availableCommands: List<String>,
    val missingCommands: List<String>,
    val diagnostics: List<String>,
)

interface RuntimeEngine {
    val mode: RuntimeCapabilityMode

    suspend fun execute(request: ShellCommandRequest): ShellCommandResult
}

interface HostBridge {
    val mode: RuntimeCapabilityMode

    suspend fun inspectHealth(): RuntimeHealthReport
}
