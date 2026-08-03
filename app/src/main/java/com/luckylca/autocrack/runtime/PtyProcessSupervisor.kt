package com.luckylca.autocrack.runtime

import android.system.Os
import java.io.File

internal class PtyProcessSupervisor(
    private val layout: RuntimeLayout,
    private val hostEngine: RootShellRuntimeEngine,
) {
    suspend fun inspect(rootPid: Int): PtyProcessTreeSnapshot {
        require(rootPid > 1) { "PTY 根进程 PID 非法" }
        layout.initialize()
        val refreshedAt = System.currentTimeMillis()
        val processSnapshotFile = File.createTempFile(
            "pty-process-table-$rootPid-",
            ".snapshot",
            layout.tempRoot,
        )
        val commandLineSnapshotFile = File.createTempFile(
            "pty-proc-cmdline-$rootPid-",
            ".snapshot",
            layout.tempRoot,
        )
        val processSnapshotStat = Os.stat(processSnapshotFile.path)

        return try {
            val processResult = hostEngine.execute(
                PtyProcessProbeScriptBuilder.buildProcessTableRequest(
                    rootPid = rootPid,
                    snapshotPath = processSnapshotFile.path,
                    rootfsPath = layout.rootfsRoot.path,
                    workingDirectory = layout.runtimeRoot.path,
                    snapshotOwnerUid = processSnapshotStat.st_uid,
                    snapshotOwnerGid = processSnapshotStat.st_gid,
                ),
            )
            val processSnapshot = readSnapshot(
                file = processSnapshotFile,
                maxBytes = MAX_PROCESS_TABLE_SNAPSHOT_BYTES,
                label = "进程表",
            )
            val baseProcesses = processSnapshot.text
                ?.takeIf(PtyProcessProbeParser::hasCompleteTable)
                ?.let { PtyProcessProbeParser.parse(it, rootPid) }
                .orEmpty()
            val fatalFailure = processSnapshotFailure(
                result = processResult,
                snapshot = processSnapshot,
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
            runCatching { processSnapshotFile.delete() }
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

    private fun processSnapshotFailure(
        result: ShellCommandResult,
        snapshot: SnapshotRead,
        processes: List<PtyProcessInfo>,
    ): String? {
        if (snapshot.failure != null) return snapshot.failure
        if (snapshot.text?.lineSequence()?.any { it.trim() == ROOT_GONE_MARKER } == true) {
            return "PTY 根进程已退出"
        }
        if (snapshot.text?.lineSequence()?.any { it.trim() == PS_UNAVAILABLE_MARKER } == true) {
            return "Debian rootfs 中缺少 /usr/bin/ps"
        }
        if (snapshot.text?.lineSequence()?.any { it.trim() == SNAPSHOT_PERMISSION_FAILED_MARKER } == true) {
            return "恢复进程表快照的 App 文件权限失败"
        }
        if (snapshot.text == null || !PtyProcessProbeParser.hasCompleteTable(snapshot.text)) {
            return result.failure ?: when {
                result.timedOut -> "进程树探测超时"
                result.cancelled -> "进程树探测被取消"
                !result.succeeded -> "进程树探测失败：exit=${result.exitCode ?: "unknown"}"
                else -> "进程表快照缺少完整起止标记"
            }
        }
        val psExitCode = PtyProcessProbeParser.parseProcessTableExitCode(snapshot.text)
        if (psExitCode != null && psExitCode != 0) {
            return "Debian ps 进程表快照失败：exit=$psExitCode"
        }
        if (processes.isEmpty()) return "没有在 Debian ps 快照中找到 PTY 根进程"
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
        const val MAX_PROCESS_TABLE_SNAPSHOT_BYTES = 4L * 1024L * 1024L
        const val MAX_COMMAND_LINE_SNAPSHOT_BYTES = 1024L * 1024L
        const val MAX_COMMAND_LINE_PIDS = 256
        const val ROOT_GONE_MARKER = "ROOT_GONE"
        const val PS_UNAVAILABLE_MARKER = "PS_UNAVAILABLE"
        const val SNAPSHOT_PERMISSION_FAILED_MARKER = "SNAPSHOT_PERMISSION_FAILED"
    }
}

internal object PtyProcessProbeScriptBuilder {
    fun buildProcessTableRequest(
        rootPid: Int,
        snapshotPath: String,
        rootfsPath: String,
        workingDirectory: String,
        snapshotOwnerUid: Int,
        snapshotOwnerGid: Int,
    ): ShellCommandRequest = ShellCommandRequest(
        command = buildProcessTableSnapshot(
            rootPid = rootPid,
            snapshotPath = snapshotPath,
            rootfsPath = rootfsPath,
            snapshotOwnerUid = snapshotOwnerUid,
            snapshotOwnerGid = snapshotOwnerGid,
        ),
        workingDirectory = workingDirectory,
        timeoutMillis = PROCESS_TABLE_PROBE_TIMEOUT_MILLIS,
        identity = HostExecutionIdentity.ROOT,
        outputMode = ShellOutputMode.DISCARD,
    )

    fun buildProcessTableSnapshot(
        rootPid: Int,
        snapshotPath: String,
        rootfsPath: String,
        snapshotOwnerUid: Int,
        snapshotOwnerGid: Int,
    ): String {
        require(rootPid > 1) { "PTY 根进程 PID 非法" }
        require(snapshotPath.isNotBlank()) { "进程表快照路径不能为空" }
        require(rootfsPath.isNotBlank()) { "rootfs 路径不能为空" }
        require(snapshotOwnerUid > 0) { "进程表快照 UID 非法" }
        require(snapshotOwnerGid > 0) { "进程表快照 GID 非法" }
        return """
            set -u
            ROOT_PID=$rootPid
            ROOTFS=${ShellEscaper.quote(rootfsPath)}
            SNAPSHOT_FILE=${ShellEscaper.quote(snapshotPath)}
            SNAPSHOT_UID=$snapshotOwnerUid
            SNAPSHOT_GID=$snapshotOwnerGid
            TMP_FILE="${'$'}SNAPSHOT_FILE.tmp.${'$'}${'$'}"
            cleanup_snapshot() { rm -f "${'$'}TMP_FILE"; }
            trap cleanup_snapshot EXIT HUP INT TERM

            if [ ! -r "/proc/${'$'}ROOT_PID/stat" ]; then
              printf 'ROOT_GONE\n' > "${'$'}SNAPSHOT_FILE"
              exit 3
            fi
            if [ ! -x "${'$'}ROOTFS/usr/bin/ps" ]; then
              printf 'PS_UNAVAILABLE\n' > "${'$'}SNAPSHOT_FILE"
              exit 4
            fi

            # A single procps invocation replaces per-PID shell traversal. The active
            # PTY already keeps /proc mounted inside the managed Debian rootfs.
            {
              printf 'PROCESS_TABLE_BEGIN\n'
              chroot "${'$'}ROOTFS" /usr/bin/env -i \
                LC_ALL=C PATH=/usr/bin:/bin \
                /usr/bin/ps -e -o pid=,ppid=,pgid=,sid=,stat=,comm=
              PS_EXIT=${'$'}?
              printf 'PROCESS_PS_EXIT=%s\n' "${'$'}PS_EXIT"
              printf 'PROCESS_TABLE_END\n'
            } > "${'$'}TMP_FILE"

            # KernelSU executes this script as root. A plain mv would replace the
            # App-owned placeholder with a root-owned inode (often mode 0600), which
            # makes the Kotlin process fail with EACCES. Restore the exact App UID/GID
            # before the atomic rename, while keeping the snapshot private.
            if ! chown "${'$'}SNAPSHOT_UID:${'$'}SNAPSHOT_GID" "${'$'}TMP_FILE" ||
               ! chmod 0600 "${'$'}TMP_FILE"; then
              printf 'SNAPSHOT_PERMISSION_FAILED\n' > "${'$'}SNAPSHOT_FILE"
              exit 5
            fi
            mv "${'$'}TMP_FILE" "${'$'}SNAPSHOT_FILE"
            trap - EXIT HUP INT TERM
            exit "${'$'}PS_EXIT"
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

    private const val PROCESS_TABLE_PROBE_TIMEOUT_MILLIS = 5_000L
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

    fun parseProcessTableExitCode(output: String): Int? = output
        .lineSequence()
        .map(String::trim)
        .firstOrNull { it.startsWith(PROCESS_PS_EXIT_PREFIX) }
        ?.removePrefix(PROCESS_PS_EXIT_PREFIX)
        ?.toIntOrNull()

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
            val line = rawLine.trim()
            when (line) {
                PROCESS_TABLE_BEGIN -> inside = true
                PROCESS_TABLE_END -> inside = false
                else -> {
                    if (!inside || line.startsWith(PROCESS_PS_EXIT_PREFIX)) return@forEach
                    parsePsRecord(line)?.let(processes::add)
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

    private fun parsePsRecord(line: String): PtyProcessInfo? {
        val match = PS_RECORD_REGEX.matchEntire(line) ?: return null
        val pid = match.groupValues[1].toIntOrNull() ?: return null
        val parentPid = match.groupValues[2].toIntOrNull() ?: return null
        val processGroupId = match.groupValues[3].toIntOrNull() ?: return null
        val sessionId = match.groupValues[4].toIntOrNull() ?: return null
        val stateCode = match.groupValues[5]
        val name = match.groupValues[6].trim()
        if (name.isBlank()) return null
        return PtyProcessInfo(
            pid = pid,
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

    private val PS_RECORD_REGEX = Regex("^\\s*(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\S+)\\s+(.+?)\\s*$")
    private const val PROCESS_PS_EXIT_PREFIX = "PROCESS_PS_EXIT="
    private const val PROCESS_TABLE_BEGIN = "PROCESS_TABLE_BEGIN"
    private const val PROCESS_TABLE_END = "PROCESS_TABLE_END"
    private const val CMDLINE_TABLE_BEGIN = "CMDLINE_TABLE_BEGIN"
    private const val CMDLINE_TABLE_END = "CMDLINE_TABLE_END"
}
