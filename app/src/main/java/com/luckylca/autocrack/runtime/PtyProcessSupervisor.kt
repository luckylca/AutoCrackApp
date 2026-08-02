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
        val processes = PtyProcessProbeParser.parse(result.stdout)
        val failure = when {
            result.stdout.lineSequence().any { it.trim() == ROOT_GONE_MARKER } ->
                "PTY 根进程已退出"
            result.succeeded && processes.isNotEmpty() -> null
            result.failure != null -> result.failure
            result.timedOut -> "进程树探测超时"
            result.cancelled -> "进程树探测被取消"
            else -> result.stderr.ifBlank {
                "进程树探测失败：exit=${result.exitCode ?: "unknown"}"
            }
        }
        return PtyProcessTreeSnapshot(
            rootPid = rootPid,
            processes = processes,
            refreshedAtEpochMillis = refreshedAt,
            failure = failure,
        )
    }

    private companion object {
        const val PROCESS_PROBE_TIMEOUT_MILLIS = 5_000L
        const val ROOT_GONE_MARKER = "ROOT_GONE"
    }
}

internal object PtyProcessProbeScriptBuilder {
    fun build(rootPid: Int): String {
        require(rootPid > 1) { "PTY 根进程 PID 非法" }
        return """
            set -u
            ROOT_PID=$rootPid
            if [ ! -d "/proc/${'$'}ROOT_PID" ]; then
              echo ROOT_GONE
              exit 3
            fi

            VISITED=""
            sanitize() {
              tr '\000\011\012\015|' '     '
            }
            status_value() {
              KEY="${'$'}1"
              FILE="${'$'}2"
              sed -n "s/^${'$'}KEY:[[:space:]]*//p" "${'$'}FILE" 2>/dev/null | head -n 1
            }
            walk_process() {
              PID="${'$'}1"
              case " ${'$'}VISITED " in
                *" ${'$'}PID "*) return ;;
              esac
              VISITED="${'$'}VISITED ${'$'}PID"

              STATUS="/proc/${'$'}PID/status"
              [ -r "${'$'}STATUS" ] || return
              NAME="${'$'}(status_value Name "${'$'}STATUS" | sanitize)"
              STATE="${'$'}(status_value State "${'$'}STATUS" | sanitize)"
              PPID="${'$'}(status_value PPid "${'$'}STATUS")"
              PGID="${'$'}(status_value NSpgid "${'$'}STATUS")"
              SID="${'$'}(status_value NSsid "${'$'}STATUS")"
              CMDLINE="${'$'}(cat "/proc/${'$'}PID/cmdline" 2>/dev/null | sanitize)"
              [ -n "${'$'}CMDLINE" ] || CMDLINE="[${'$'}NAME]"
              [ -n "${'$'}PPID" ] || PPID=0
              [ -n "${'$'}PGID" ] || PGID=0
              [ -n "${'$'}SID" ] || SID=0
              printf 'P|%s|%s|%s|%s|%s|%s|%s\n' \
                "${'$'}PID" "${'$'}PPID" "${'$'}PGID" "${'$'}SID" \
                "${'$'}STATE" "${'$'}NAME" "${'$'}CMDLINE"

              CHILDREN="${'$'}(cat "/proc/${'$'}PID/task/${'$'}PID/children" 2>/dev/null || true)"
              for CHILD in ${'$'}CHILDREN; do
                walk_process "${'$'}CHILD"
              done
            }

            echo PROCESS_TREE_BEGIN
            walk_process "${'$'}ROOT_PID"
            echo PROCESS_TREE_END
        """.trimIndent()
    }
}

internal object PtyProcessProbeParser {
    fun parse(output: String): List<PtyProcessInfo> {
        var inside = false
        val processes = mutableListOf<PtyProcessInfo>()
        output.lineSequence().forEach { rawLine ->
            val line = rawLine.trimEnd()
            when (line) {
                "PROCESS_TREE_BEGIN" -> inside = true
                "PROCESS_TREE_END" -> inside = false
                else -> {
                    if (!inside || !line.startsWith("P|")) return@forEach
                    val fields = line.split('|', limit = 8)
                    if (fields.size != 8) return@forEach
                    val pid = fields[1].toIntOrNull() ?: return@forEach
                    val parentPid = fields[2].toIntOrNull() ?: 0
                    val processGroupId = fields[3].toIntOrNull() ?: 0
                    val sessionId = fields[4].toIntOrNull() ?: 0
                    processes += PtyProcessInfo(
                        pid = pid,
                        parentPid = parentPid,
                        processGroupId = processGroupId,
                        sessionId = sessionId,
                        state = fields[5].trim(),
                        name = fields[6].trim(),
                        commandLine = fields[7].trim(),
                    )
                }
            }
        }
        return processes.distinctBy(PtyProcessInfo::pid)
    }
}
