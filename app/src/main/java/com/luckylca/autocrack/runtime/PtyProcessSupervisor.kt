package com.luckylca.autocrack.runtime

import java.io.File

internal class PtyProcessSupervisor(
    private val layout: RuntimeLayout,
    private val hostEngine: RootShellRuntimeEngine,
) {
    suspend fun inspect(rootPid: Int): PtyProcessTreeSnapshot {
        require(rootPid > 1) { "PTY 根进程 PID 非法" }
        layout.initialize()
        val refreshedAt = System.currentTimeMillis()
        val statSnapshotFile = File.createTempFile(
            "pty-proc-stat-$rootPid-",
            ".snapshot",
            layout.tempRoot,
        )
        val commandLineSnapshotFile = File.createTempFile(
            "pty-proc-cmdline-$rootPid-",
            ".snapshot",
            layout.tempRoot,
        )

        return try {
            val statResult = hostEngine.execute(
                PtyProcessProbeScriptBuilder.buildStatRequest(
                    rootPid = rootPid,
                    snapshotPath = statSnapshotFile.path,
                    workingDirectory = layout.runtimeRoot.path,
                ),
            )
            val statSnapshot = readSnapshot(
                file = statSnapshotFile,
                maxBytes = MAX_PROCESS_STAT_SNAPSHOT_BYTES,
                label = "进程状态",
            )
            val baseProcesses = statSnapshot.text
                ?.takeIf(PtyProcessProbeParser::hasCompleteTable)
                ?.let { PtyProcessProbeParser.parse(it, rootPid) }
                .orEmpty()
            val fatalFailure = statSnapshotFailure(
                result = statResult,
                snapshot = statSnapshot,
                processes = baseProcesses,
            )
            if (fatalFailure != null) {
                return PtyProcessTreeSnapshot(
                    rootPid = rootPid,
                    processes = baseProcesses,
                    refreshedAtEpochMillis = refreshedAt,
                    failure = fatalFailure,
                )
            }

            val commandLineResult = enrichCommandLines(
                processes = baseProcesses,
                snapshotFile = commandLineSnapshotFile,
            )
            val failure = when {
                commandLineResult.processes.size == 1 ->
                    "仅识别到 PTY 根进程；当前内核可能限制了其他进程的 /proc 可见性"
                commandLineResult.failure != null -> commandLineResult.failure
                else -> null
            }
            PtyProcessTreeSnapshot(
                rootPid = rootPid,
                processes = commandLineResult.processes,
                refreshedAtEpochMillis = refreshedAt,
                failure = failure,
            )
        } finally {
            runCatching { statSnapshotFile.delete() }
            runCatching { commandLineSnapshotFile.delete() }
        }
    }

    private suspend fun enrichCommandLines(
        processes: List<PtyProcessInfo>,
        snapshotFile: File,
    ): CommandLineEnrichmentResult {
        if (processes.isEmpty()) {
            return CommandLineEnrichmentResult(processes = processes, failure = null)
        }

        val selectedPids = processes
            .map(PtyProcessInfo::pid)
            .distinct()
            .take(MAX_COMMAND_LINE_PIDS)
        val result = hostEngine.execute(
            PtyProcessProbeScriptBuilder.buildCommandLineRequest(
                pids = selectedPids,
                snapshotPath = snapshotFile.path,
                workingDirectory = layout.runtimeRoot.path,
            ),
        )
        val snapshot = readSnapshot(
            file = snapshotFile,
            maxBytes = MAX_COMMAND_LINE_SNAPSHOT_BYTES,
            label = "进程命令行",
        )
        val complete = snapshot.text?.let(PtyProcessProbeParser::hasCompleteCommandLineTable) == true
        if (!complete) {
            val failure = snapshot.failure ?: result.failure ?: when {
                result.timedOut -> "进程命令行补全超时"
                result.cancelled -> "进程命令行补全被取消"
                !result.succeeded -> "进程命令行补全失败：exit=${result.exitCode ?: "unknown"}"
                else -> "进程命令行快照缺少完整起止标记"
            }
            return CommandLineEnrichmentResult(
                processes = processes,
                failure = "进程树已生成，但$failure",
            )
        }

        val commandLines = PtyProcessProbeParser.parseCommandLines(checkNotNull(snapshot.text))
        return CommandLineEnrichmentResult(
            processes = PtyProcessProbeParser.enrichCommandLines(processes, commandLines),
            failure = null,
        )
    }

    private fun statSnapshotFailure(
        result: ShellCommandResult,
        snapshot: SnapshotRead,
        processes: List<PtyProcessInfo>,
    ): String? {
        if (snapshot.failure != null) return snapshot.failure
        if (snapshot.text?.lineSequence()?.any { it.trim() == ROOT_GONE_MARKER } == true) {
            return "PTY 根进程已退出"
        }
        if (snapshot.text == null || !PtyProcessProbeParser.hasCompleteTable(snapshot.text)) {
            return result.failure ?: when {
                result.timedOut -> "进程树探测超时"
                result.cancelled -> "进程树探测被取消"
                !result.succeeded -> "进程树探测失败：exit=${result.exitCode ?: "unknown"}"
                else -> "进程状态快照缺少完整起止标记"
            }
        }
        if (processes.isEmpty()) return "没有在 /proc 快照中找到 PTY 根进程"
        return null
    }

    private fun readSnapshot(
        file: File,
        maxBytes: Long,
        label: String,
    ): SnapshotRead {
        val length = file.length()
        if (length > maxBytes) {
            return SnapshotRead(
                text = null,
                failure = "$label 快照过大：$length B，限制为 $maxBytes B",
            )
        }
        return runCatching {
            SnapshotRead(text = file.readText(Charsets.UTF_8), failure = null)
        }.getOrElse { exception ->
            SnapshotRead(
                text = null,
                failure = "读取$label 快照失败：${exception.message ?: exception::class.java.name}",
            )
        }
    }

    private data class SnapshotRead(
        val text: String?,
        val failure: String?,
    )

    private data class CommandLineEnrichmentResult(
        val processes: List<PtyProcessInfo>,
        val failure: String?,
    )

    private companion object {
        const val MAX_PROCESS_STAT_SNAPSHOT_BYTES = 4L * 1024L * 1024L
        const val MAX_COMMAND_LINE_SNAPSHOT_BYTES = 1024L * 1024L
        const val MAX_COMMAND_LINE_PIDS = 256
        const val ROOT_GONE_MARKER = "ROOT_GONE"
    }
}

internal object PtyProcessProbeScriptBuilder {
    fun buildStatRequest(
        rootPid: Int,
        snapshotPath: String,
        workingDirectory: String,
    ): ShellCommandRequest = ShellCommandRequest(
        command = buildStatSnapshot(rootPid, snapshotPath),
        workingDirectory = workingDirectory,
        timeoutMillis = PROCESS_STAT_PROBE_TIMEOUT_MILLIS,
        identity = HostExecutionIdentity.ROOT,
        outputMode = ShellOutputMode.DISCARD,
    )

    fun buildStatSnapshot(rootPid: Int, snapshotPath: String): String {
        require(rootPid > 1) { "PTY 根进程 PID 非法" }
        require(snapshotPath.isNotBlank()) { "进程状态快照路径不能为空" }
        return """
            set -u
            ROOT_PID=$rootPid
            SNAPSHOT_FILE=${ShellEscaper.quote(snapshotPath)}
            if [ ! -r "/proc/${'$'}ROOT_PID/stat" ]; then
              printf 'ROOT_GONE\n' > "${'$'}SNAPSHOT_FILE"
              exit 3
            fi

            # The global phase reads only stat records with the shell built-in read.
            # It does not spawn cat/tr once per PID; command lines are fetched later
            # only for the small PTY descendant set selected by Kotlin.
            {
              printf 'PROCESS_TABLE_BEGIN\n'
              for PROC_DIR in /proc/[0-9]*; do
                [ -d "${'$'}PROC_DIR" ] || continue
                PID="${'$'}{PROC_DIR##*/}"
                STAT_FILE="${'$'}PROC_DIR/stat"
                [ -r "${'$'}STAT_FILE" ] || continue

                STAT_LINE=''
                IFS= read -r STAT_LINE < "${'$'}STAT_FILE" 2>/dev/null || continue
                [ -n "${'$'}STAT_LINE" ] || continue
                printf 'R|%s|%s\n' "${'$'}PID" "${'$'}STAT_LINE"
              done
              printf 'PROCESS_TABLE_END\n'
            } > "${'$'}SNAPSHOT_FILE"
        """.trimIndent()
    }

    fun buildCommandLineRequest(
        pids: List<Int>,
        snapshotPath: String,
        workingDirectory: String,
    ): ShellCommandRequest = ShellCommandRequest(
        command = buildCommandLineSnapshot(pids, snapshotPath),
        workingDirectory = workingDirectory,
        timeoutMillis = COMMAND_LINE_PROBE_TIMEOUT_MILLIS,
        identity = HostExecutionIdentity.ROOT,
        outputMode = ShellOutputMode.DISCARD,
    )

    fun buildCommandLineSnapshot(pids: List<Int>, snapshotPath: String): String {
        require(pids.isNotEmpty()) { "进程 PID 列表不能为空" }
        require(pids.all { it > 1 }) { "进程 PID 非法" }
        require(snapshotPath.isNotBlank()) { "进程命令行快照路径不能为空" }
        val selectedPids = pids.distinct().take(MAX_COMMAND_LINE_PIDS)
        val pidWords = selectedPids.joinToString(" ")
        return """
            set -u
            SNAPSHOT_FILE=${ShellEscaper.quote(snapshotPath)}
            {
              printf 'CMDLINE_TABLE_BEGIN\n'
              for PID in $pidWords; do
                CMDLINE_FILE="/proc/${'$'}PID/cmdline"
                [ -r "${'$'}CMDLINE_FILE" ] || continue
                CMDLINE="${'$'}(tr '\000\011\012\015' '    ' < "${'$'}CMDLINE_FILE" 2>/dev/null)"
                printf 'C|%s|%s\n' "${'$'}PID" "${'$'}CMDLINE"
              done
              printf 'CMDLINE_TABLE_END\n'
            } > "${'$'}SNAPSHOT_FILE"
        """.trimIndent()
    }

    private const val PROCESS_STAT_PROBE_TIMEOUT_MILLIS = 10_000L
    private const val COMMAND_LINE_PROBE_TIMEOUT_MILLIS = 5_000L
    private const val MAX_COMMAND_LINE_PIDS = 256
}

internal object PtyProcessProbeParser {
    fun hasCompleteTable(output: String): Boolean {
        val begin = output.indexOf(PROCESS_TABLE_BEGIN)
        val end = output.indexOf(PROCESS_TABLE_END)
        return begin >= 0 && end > begin
    }

    fun hasCompleteCommandLineTable(output: String): Boolean {
        val begin = output.indexOf(CMDLINE_TABLE_BEGIN)
        val end = output.indexOf(CMDLINE_TABLE_END)
        return begin >= 0 && end > begin
    }

    fun parse(output: String, rootPid: Int): List<PtyProcessInfo> {
        require(rootPid > 1) { "PTY 根进程 PID 非法" }
        if (!hasCompleteTable(output)) return emptyList()
        val allProcesses = parseProcessTable(output)
        return selectRootAndDescendants(allProcesses, rootPid)
    }

    internal fun parseProcessTable(output: String): List<PtyProcessInfo> {
        var inside = false
        val processes = mutableListOf<PtyProcessInfo>()
        output.lineSequence().forEach { rawLine ->
            val line = rawLine.trimEnd()
            when (line) {
                PROCESS_TABLE_BEGIN -> inside = true
                PROCESS_TABLE_END -> inside = false
                else -> {
                    if (!inside || !line.startsWith("R|")) return@forEach
                    val fields = line.split('|', limit = 3)
                    if (fields.size != 3) return@forEach
                    val expectedPid = fields[1].toIntOrNull() ?: return@forEach
                    parseStatRecord(
                        expectedPid = expectedPid,
                        statLine = fields[2],
                    )?.let(processes::add)
                }
            }
        }
        return processes.distinctBy(PtyProcessInfo::pid)
    }

    internal fun parseCommandLines(output: String): Map<Int, String> {
        if (!hasCompleteCommandLineTable(output)) return emptyMap()
        var inside = false
        val commandLines = linkedMapOf<Int, String>()
        output.lineSequence().forEach { rawLine ->
            val line = rawLine.trimEnd()
            when (line) {
                CMDLINE_TABLE_BEGIN -> inside = true
                CMDLINE_TABLE_END -> inside = false
                else -> {
                    if (!inside || !line.startsWith("C|")) return@forEach
                    val fields = line.split('|', limit = 3)
                    if (fields.size != 3) return@forEach
                    val pid = fields[1].toIntOrNull() ?: return@forEach
                    commandLines[pid] = fields[2].trim()
                }
            }
        }
        return commandLines
    }

    internal fun enrichCommandLines(
        processes: List<PtyProcessInfo>,
        commandLines: Map<Int, String>,
    ): List<PtyProcessInfo> = processes.map { process ->
        val commandLine = commandLines[process.pid]
            ?.takeIf(String::isNotBlank)
            ?: process.commandLine
        process.copy(commandLine = commandLine)
    }

    internal fun selectRootAndDescendants(
        processes: List<PtyProcessInfo>,
        rootPid: Int,
    ): List<PtyProcessInfo> {
        val processByPid = processes.associateBy(PtyProcessInfo::pid)
        if (rootPid !in processByPid) return emptyList()

        val selectedPids = linkedSetOf(rootPid)
        var changed: Boolean
        do {
            changed = false
            processes.forEach { process ->
                if (process.pid !in selectedPids && process.parentPid in selectedPids) {
                    selectedPids += process.pid
                    changed = true
                }
            }
        } while (changed)

        return selectedPids.mapNotNull(processByPid::get)
    }

    private fun parseStatRecord(
        expectedPid: Int,
        statLine: String,
    ): PtyProcessInfo? {
        val openParen = statLine.indexOf('(')
        val closeParen = statLine.lastIndexOf(") ")
        if (openParen <= 0 || closeParen <= openParen) return null

        val statPid = statLine.substring(0, openParen).trim().toIntOrNull() ?: return null
        if (statPid != expectedPid) return null

        val name = statLine.substring(openParen + 1, closeParen).trim()
        val remainingFields = statLine.substring(closeParen + 2)
            .trim()
            .split(WHITESPACE_REGEX)
        if (remainingFields.size < MINIMUM_STAT_FIELDS) return null

        val stateCode = remainingFields[0]
        val parentPid = remainingFields[1].toIntOrNull() ?: return null
        val processGroupId = remainingFields[2].toIntOrNull() ?: return null
        val sessionId = remainingFields[3].toIntOrNull() ?: return null
        return PtyProcessInfo(
            pid = expectedPid,
            parentPid = parentPid,
            processGroupId = processGroupId,
            sessionId = sessionId,
            state = stateLabel(stateCode),
            name = name,
            commandLine = "[$name]",
        )
    }

    private fun stateLabel(stateCode: String): String {
        val description = when (stateCode.firstOrNull()) {
            'R' -> "running"
            'S' -> "sleeping"
            'D' -> "disk sleep"
            'T' -> "stopped"
            't' -> "tracing stop"
            'Z' -> "zombie"
            'X', 'x' -> "dead"
            'I' -> "idle"
            else -> "unknown"
        }
        return "$stateCode ($description)"
    }

    private val WHITESPACE_REGEX = Regex("\\s+")
    private const val MINIMUM_STAT_FIELDS = 4
    private const val PROCESS_TABLE_BEGIN = "PROCESS_TABLE_BEGIN"
    private const val PROCESS_TABLE_END = "PROCESS_TABLE_END"
    private const val CMDLINE_TABLE_BEGIN = "CMDLINE_TABLE_BEGIN"
    private const val CMDLINE_TABLE_END = "CMDLINE_TABLE_END"
}
