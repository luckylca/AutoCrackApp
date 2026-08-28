package com.luckylca.autocrack.agent

import com.luckylca.autocrack.runtime.HostExecutionIdentity
import com.luckylca.autocrack.runtime.RuntimeEngine
import com.luckylca.autocrack.runtime.ShellCommandRequest
import com.luckylca.autocrack.runtime.ShellCommandResult
import com.luckylca.autocrack.runtime.WorkspaceFileEntry
import com.luckylca.autocrack.runtime.WorkspaceFileService
import org.json.JSONObject

/**
 * Phase 6 raw action runtime.
 *
 * This intentionally exposes only a few primitive actions. CLI tools remain their native CLI
 * tools inside the rootfs; the model writes Bash/Python and composes them directly.
 */
class RawBashAgentToolExecutor(
    private val packageName: String? = null,
    private val chroot: RuntimeEngine,
    private val host: RuntimeEngine,
    private val workspaceFiles: WorkspaceFileService,
    private val dynamicToolsAllowed: Boolean = false,
    private val availableToolCommands: List<String> = emptyList(),
) : AgentToolExecutor {
    override val tools: List<AgentToolDefinition> = buildDefinitions()

    override suspend fun dispatch(toolName: String, arguments: JSONObject): String = when (toolName) {
        TOOL_EXEC_BASH -> execBash(arguments)
        TOOL_READ_FILE -> readFile(arguments)
        TOOL_WRITE_FILE -> writeFile(arguments)
        TOOL_KILL_PROCESS -> killProcess(arguments)
        else -> error("Unknown Raw Bash Agent tool: $toolName")
    }

    private suspend fun execBash(arguments: JSONObject): String {
        val script = arguments.requireString("script")
        require(script.length <= MAX_SCRIPT_CHARS) { "script is too large" }
        val cwd = normalizeWorkspaceCwd(arguments.optString("cwd", DEFAULT_CHROOT_CWD))
        val timeoutMillis = arguments.optLongOrDefault("timeout_ms", ShellCommandRequest.DEFAULT_TIMEOUT_MILLIS)
        require(timeoutMillis in ShellCommandRequest.MIN_TIMEOUT_MILLIS..ShellCommandRequest.MAX_TIMEOUT_MILLIS) {
            "timeout_ms must be within ${ShellCommandRequest.MIN_TIMEOUT_MILLIS}..${ShellCommandRequest.MAX_TIMEOUT_MILLIS}"
        }
        val stdin = arguments.optNullableString("stdin")
        val result = chroot.execute(
            ShellCommandRequest(
                command = script,
                workingDirectory = cwd,
                environment = buildMap {
                    put("AUTOC_AGENT_MODE", "raw_bash")
                    put("AUTOC_TOOLPACK_COMMANDS", availableToolCommands.distinct().sorted().joinToString(","))
                    packageName?.takeIf(String::isNotBlank)?.let { put("AUTOC_TARGET_PACKAGE", it) }
                },
                stdin = stdin,
                timeoutMillis = timeoutMillis,
                identity = HostExecutionIdentity.ROOT,
            ),
        )
        return shellResultJson(TOOL_EXEC_BASH, "debian-chroot", result)
            .put("cwd", cwd)
            .put("workspace", DEFAULT_CHROOT_CWD)
            .apply { packageName?.takeIf(String::isNotBlank)?.let { put("targetPackage", it) } }
            .put("toolpackCommands", availableToolCommands.distinct().sorted().joinToString(","))
            .toString()
    }

    private suspend fun readFile(arguments: JSONObject): String {
        val path = arguments.requireString("path")
        val maxChars = arguments.optIntOrDefault("max_chars", WorkspaceFileService.DEFAULT_MAX_TEXT_CHARS)
        require(maxChars in 1..WorkspaceFileService.MAX_TEXT_CHARS) { "max_chars is out of range" }
        val content = workspaceFiles.readText(path, maxChars)
        return JSONObject()
            .put("ok", true)
            .put("tool", TOOL_READ_FILE)
            .put("path", path)
            .put("workspaceRoot", workspaceFiles.rootPath())
            .put("content", content)
            .put("truncated", content.endsWith("\n...[file preview truncated]"))
            .toString()
    }

    private suspend fun writeFile(arguments: JSONObject): String {
        val path = arguments.requireString("path")
        val content = arguments.requireString("content")
        val append = arguments.optBoolean("append", false)
        val entry = workspaceFiles.writeText(path, content, append)
        return JSONObject()
            .put("ok", true)
            .put("tool", TOOL_WRITE_FILE)
            .put("path", path)
            .put("append", append)
            .put("entry", entry.toJson())
            .put("workspaceRoot", workspaceFiles.rootPath())
            .toString()
    }

    private suspend fun killProcess(arguments: JSONObject): String {
        val pid = arguments.optInt("pid", -1)
        require(pid > 1) { "pid must be greater than 1" }
        val signal = arguments.optString("signal", "TERM").uppercase()
        require(signal in ALLOWED_SIGNALS) { "signal must be one of ${ALLOWED_SIGNALS.joinToString()}" }
        val result = host.execute(
            ShellCommandRequest(
                command = """
                    set -u
                    pid=$pid
                    before=missing
                    [ -d /proc/${'$'}pid ] && before=present
                    kill -$signal "${'$'}pid" 2>/dev/null
                    rc=${'$'}?
                    after=missing
                    [ -d /proc/${'$'}pid ] && after=present
                    printf 'pid=%s\nsignal=%s\nbefore=%s\nafter=%s\nkill_rc=%s\n' "${'$'}pid" "$signal" "${'$'}before" "${'$'}after" "${'$'}rc"
                    exit "${'$'}rc"
                """.trimIndent(),
                workingDirectory = "/",
                timeoutMillis = KILL_TIMEOUT_MILLIS,
                identity = HostExecutionIdentity.ROOT,
            ),
        )
        return shellResultJson(TOOL_KILL_PROCESS, "android-root", result)
            .put("pid", pid)
            .put("signal", signal)
            .toString()
    }

    private fun shellResultJson(tool: String, runtime: String, result: ShellCommandResult): JSONObject = JSONObject()
        .put("ok", result.succeeded)
        .put("tool", tool)
        .put("runtime", runtime)
        .put("exitCode", result.exitCode ?: JSONObject.NULL)
        .put("timedOut", result.timedOut)
        .put("cancelled", result.cancelled)
        .put("failure", result.failure ?: JSONObject.NULL)
        .put("stdout", result.stdout.take(MAX_RETAINED_OUTPUT_CHARS))
        .put("stderr", result.stderr.take(MAX_RETAINED_OUTPUT_CHARS))
        .put("stdoutTruncated", result.stdoutTruncated || result.stdout.length > MAX_RETAINED_OUTPUT_CHARS)
        .put("stderrTruncated", result.stderrTruncated || result.stderr.length > MAX_RETAINED_OUTPUT_CHARS)
        .put("durationMillis", result.durationMillis)
        .put("auditFile", result.auditFilePath)

    companion object {
        const val TOOL_EXEC_BASH = "exec_bash"
        const val TOOL_READ_FILE = "read_file"
        const val TOOL_WRITE_FILE = "write_file"
        const val TOOL_KILL_PROCESS = "kill_process"
        const val DEFAULT_CHROOT_CWD = "/workspace"
        const val MAX_SCRIPT_CHARS = 200_000
        const val MAX_RETAINED_OUTPUT_CHARS = 200_000
        private const val KILL_TIMEOUT_MILLIS = 3_000L
        private val ALLOWED_SIGNALS = setOf("TERM", "KILL", "INT", "HUP")

        internal fun normalizeWorkspaceCwd(cwd: String): String {
            val value = cwd.ifBlank { DEFAULT_CHROOT_CWD }
            require(value.startsWith('/')) { "cwd must be an absolute chroot path" }
            require(!value.contains("..")) { "cwd must not contain .." }
            require(value == DEFAULT_CHROOT_CWD || value.startsWith("$DEFAULT_CHROOT_CWD/")) {
                "cwd must stay inside $DEFAULT_CHROOT_CWD"
            }
            return value.trimEnd('/').ifBlank { DEFAULT_CHROOT_CWD }
        }

        fun buildDefinitions(): List<AgentToolDefinition> = listOf(
            AgentToolDefinition(
                TOOL_EXEC_BASH,
                "Execute Bash in the managed Debian rootfs workspace. Specialized CLI commands come from installed toolpacks and are listed in the session context. Boundary: /workspace cwd, timeout, output cap and audit log.",
                AgentJsonSchema.objectSchema(
                    JSONObject()
                        .put("script", stringSchema("Bash script to run."))
                        .put("cwd", stringSchema("Absolute chroot cwd under /workspace. Default: /workspace."))
                        .put("stdin", stringSchema("Optional stdin passed to the script."))
                        .put("timeout_ms", integerSchema("Execution timeout in milliseconds.")),
                    required = listOf("script"),
                ),
            ),
            AgentToolDefinition(
                TOOL_READ_FILE,
                "Read a UTF-8 text file from the managed AutoCrack workspace.",
                AgentJsonSchema.objectSchema(
                    JSONObject()
                        .put("path", stringSchema("Workspace-relative path."))
                        .put("max_chars", integerSchema("Maximum characters to return.")),
                    required = listOf("path"),
                ),
            ),
            AgentToolDefinition(
                TOOL_WRITE_FILE,
                "Write or append a UTF-8 text file inside the managed AutoCrack workspace.",
                AgentJsonSchema.objectSchema(
                    JSONObject()
                        .put("path", stringSchema("Workspace-relative path."))
                        .put("content", stringSchema("Text content to write."))
                        .put("append", booleanSchema("Append instead of overwrite.")),
                    required = listOf("path", "content"),
                ),
            ),
            AgentToolDefinition(
                TOOL_KILL_PROCESS,
                "Kill a process by PID as the runtime kill switch. Signals: TERM, KILL, INT, HUP.",
                AgentJsonSchema.objectSchema(
                    JSONObject()
                        .put("pid", integerSchema("Process id greater than 1."))
                        .put("signal", stringSchema("TERM, KILL, INT or HUP. Default: TERM.")),
                    required = listOf("pid"),
                ),
            ),
        )

        private fun stringSchema(description: String): JSONObject = JSONObject()
            .put("type", "string")
            .put("description", description)

        private fun integerSchema(description: String): JSONObject = JSONObject()
            .put("type", "integer")
            .put("description", description)

        private fun booleanSchema(description: String): JSONObject = JSONObject()
            .put("type", "boolean")
            .put("description", description)
    }
}

private fun JSONObject.requireString(name: String): String {
    require(has(name) && !isNull(name)) { "missing required string: $name" }
    return getString(name)
}

private fun JSONObject.optNullableString(name: String): String? =
    if (has(name) && !isNull(name)) getString(name) else null

private fun JSONObject.optLongOrDefault(name: String, defaultValue: Long): Long =
    if (has(name) && !isNull(name)) getLong(name) else defaultValue

private fun JSONObject.optIntOrDefault(name: String, defaultValue: Int): Int =
    if (has(name) && !isNull(name)) getInt(name) else defaultValue

private fun WorkspaceFileEntry.toJson(): JSONObject = JSONObject()
    .put("relativePath", relativePath)
    .put("name", name)
    .put("directory", directory)
    .put("sizeBytes", sizeBytes)
    .put("lastModifiedEpochMillis", lastModifiedEpochMillis)
