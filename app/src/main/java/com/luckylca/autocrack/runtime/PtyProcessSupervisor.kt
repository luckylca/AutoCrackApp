package com.luckylca.autocrack.runtime

internal class PtyProcessSupervisor(
    private val layout: RuntimeLayout,
    private val hostEngine: RootShellRuntimeEngine,
) {
    suspend fun inspect(rootPid: Int): PtyProcessTreeSnapshot {
        require(rootPid > 1) { "PTY 根进程 PID 非法" }
        val refreshedAt = System.currentTimeMillis()
        val result = hostEngine.execute(
            ShellCommandRequest(
                command = PtyProcessProbeScriptBuilder.build(rootPid),
                workingDirectory = layout.runtimeRoot.path,
                timeoutMillis = PROCESS_PROBE_TIMEOUT_MILLIS,
                identity = HostExecutionIdentity.ROOT,
            ),
        )
        val processes = PtyProcessProbeParser.parse(result.stdout, rootPid)
        val failure = when {
            result.stdout.lineSequence().any { it.trim() == ROOT_GONE_MARKER } ->
                "PTY 根进程已退出"
            result.stdoutTruncated ->
                "进程表输出被截断，无法生成可靠进程树"
            result.failure != null -> result.failure
            result.timedOut -> "进程树探测超时"
            result.cancelled -> "进程树探测被取消"
            !result.succeeded -> result.stderr.ifBlank {
                "进程树探测失败：exit=${result.exitCode ?: "unknown"}"
            }
            processes.isEmpty() -> "没有在 /proc 快照中找到 PTY 根进程"
            processes.size == 1 ->
                "仅识别到 PTY 根进程；当前内核可能限制了其他进程的 /proc 可见性"
            else -> null
        }
        return PtyProcessTreeSnapshot(
            rootPid = rootPid,
            processes = processes,
            refreshedAtEpochMillis = refreshedAt,
            failure = failure,
        )
    }

    private companion object {
        const val PROCESS_PROBE_TIMEOUT_MILLIS = 10_000L
        const val ROOT_GONE_MARKER = "ROOT_GONE"
    }
}

internal object PtyProcessProbeScriptBuilder {
    fun build(rootPid: Int): String {
        require(rootPid > 1) { "PTY 根进程 PID 非法" }
        return """
            set -u
            ROOT_PID=$rootPid
            if [ ! -r "/proc/${'$'}ROOT_PID/stat" ]; then
              echo ROOT_GONE
              exit 3
            fi

            sanitize() {
              tr '\000\011\012\015|' '     '
            }

            echo PROCESS_TABLE_BEGIN
            for PROC_DIR in /proc/[0-9]*; do
              [ -d "${'$'}PROC_DIR" ] || continue
              PID="${'$'}{PROC_DIR##*/}"
              STAT_FILE="${'$'}PROC_DIR/stat"
              [ -r "${'$'}STAT_FILE" ] || continue

              STAT_LINE="${'$'}(cat "${'$'}STAT_FILE" 2>/dev/null | sanitize)"
              [ -n "${'$'}STAT_LINE" ] || continue
              CMDLINE="${'$'}(cat "${'$'}PROC_DIR/cmdline" 2>/dev/null | sanitize)"
              printf 'R|%s|%s|%s\n' "${'$'}PID" "${'$'}STAT_LINE" "${'$'}CMDLINE"
            done
            echo PROCESS_TABLE_END
        """.trimIndent()
    }
}

internal object PtyProcessProbeParser {
    fun parse(output: String, rootPid: Int): List<PtyProcessInfo> {
        require(rootPid > 1) { "PTY 根进程 PID 非法" }
        val allProcesses = parseProcessTable(output)
        return selectRootAndDescendants(allProcesses, rootPid)
    }

    internal fun parseProcessTable(output: String): List<PtyProcessInfo> {
        var inside = false
        val processes = mutableListOf<PtyProcessInfo>()
        output.lineSequence().forEach { rawLine ->
            val line = rawLine.trimEnd()
            when (line) {
                "PROCESS_TABLE_BEGIN" -> inside = true
                "PROCESS_TABLE_END" -> inside = false
                else -> {
                    if (!inside || !line.startsWith("R|")) return@forEach
                    val fields = line.split('|', limit = 4)
                    if (fields.size != 4) return@forEach
                    val expectedPid = fields[1].toIntOrNull() ?: return@forEach
                    parseStatRecord(
                        expectedPid = expectedPid,
                        statLine = fields[2],
                        commandLine = fields[3],
                    )?.let(processes::add)
                }
            }
        }
        return processes.distinctBy(PtyProcessInfo::pid)
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
        commandLine: String,
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
            commandLine = commandLine.trim().ifBlank { "[$name]" },
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
}
