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
        val snapshotFile = File.createTempFile(
            "pty-proc-$rootPid-",
            ".snapshot",
            layout.tempRoot,
        )

        return try {
            val result = hostEngine.execute(
                ShellCommandRequest(
                    command = PtyProcessProbeScriptBuilder.build(rootPid, snapshotFile.path),
                    workingDirectory = layout.runtimeRoot.path,
                    timeoutMillis = PROCESS_PROBE_TIMEOUT_MILLIS,
                    identity = HostExecutionIdentity.ROOT,
                ),
            )
            val snapshotRead = readSnapshot(snapshotFile)
            val processes = snapshotRead.text
                ?.takeIf(PtyProcessProbeParser::hasCompleteTable)
                ?.let { PtyProcessProbeParser.parse(it, rootPid) }
                .orEmpty()
            val failure = when {
                snapshotRead.failure != null -> snapshotRead.failure
                snapshotRead.text?.lineSequence()?.any { it.trim() == ROOT_GONE_MARKER } == true ->
                    "PTY 根进程已退出"
                snapshotRead.text == null || !PtyProcessProbeParser.hasCompleteTable(snapshotRead.text) ->
                    result.failure ?: when {
                        result.timedOut -> "进程树探测超时"
                        result.cancelled -> "进程树探测被取消"
                        !result.succeeded -> result.stderr.ifBlank {
                            "进程树探测失败：exit=${result.exitCode ?: "unknown"}"
                        }
                        else -> "进程表快照缺少完整起止标记"
                    }
                processes.isEmpty() -> "没有在 /proc 快照中找到 PTY 根进程"
                processes.size == 1 ->
                    "仅识别到 PTY 根进程；当前内核可能限制了其他进程的 /proc 可见性"
                else -> null
            }
            PtyProcessTreeSnapshot(
                rootPid = rootPid,
                processes = processes,
                refreshedAtEpochMillis = refreshedAt,
                failure = failure,
            )
        } finally {
            runCatching { snapshotFile.delete() }
        }
    }

    private fun readSnapshot(file: File): SnapshotRead {
        val length = file.length()
        if (length > MAX_PROCESS_SNAPSHOT_BYTES) {
            return SnapshotRead(
                text = null,
                failure = "进程表快照过大：$length B，限制为 $MAX_PROCESS_SNAPSHOT_BYTES B",
            )
        }
        return runCatching {
            SnapshotRead(text = file.readText(Charsets.UTF_8), failure = null)
        }.getOrElse { exception ->
            SnapshotRead(
                text = null,
                failure = "读取进程表快照失败：${exception.message ?: exception::class.java.name}",
            )
        }
    }

    private data class SnapshotRead(
        val text: String?,
        val failure: String?,
    )

    private companion object {
        const val PROCESS_PROBE_TIMEOUT_MILLIS = 10_000L
        const val MAX_PROCESS_SNAPSHOT_BYTES = 4L * 1024L * 1024L
        const val ROOT_GONE_MARKER = "ROOT_GONE"
    }
}

internal object PtyProcessProbeScriptBuilder {
    fun build(rootPid: Int, snapshotPath: String): String {
        require(rootPid > 1) { "PTY 根进程 PID 非法" }
        require(snapshotPath.isNotBlank()) { "进程表快照路径不能为空" }
        return """
            set -u
            ROOT_PID=$rootPid
            SNAPSHOT_FILE=${ShellEscaper.quote(snapshotPath)}
            if [ ! -r "/proc/${'$'}ROOT_PID/stat" ]; then
              printf 'ROOT_GONE\n' > "${'$'}SNAPSHOT_FILE"
              exit 3
            fi

            sanitize() {
              tr '\000\011\012\015|' '     '
            }

            # Capture the Root-only process table into an app-created private file.
            # This avoids Android Process pipe shutdown races for larger snapshots.
            {
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
            } > "${'$'}SNAPSHOT_FILE"
        """.trimIndent()
    }
}

internal object PtyProcessProbeParser {
    fun hasCompleteTable(output: String): Boolean {
        val begin = output.indexOf(PROCESS_TABLE_BEGIN)
        val end = output.indexOf(PROCESS_TABLE_END)
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
    private const val PROCESS_TABLE_BEGIN = "PROCESS_TABLE_BEGIN"
    private const val PROCESS_TABLE_END = "PROCESS_TABLE_END"
}
