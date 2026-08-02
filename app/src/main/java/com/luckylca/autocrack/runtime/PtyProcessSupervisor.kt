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
              STAT_FILE="/proc/${'$'}PID/stat"
              [ -r "${'$'}STATUS" ] || return
              [ -r "${'$'}STAT_FILE" ] || return

              NAME="${'$'}(status_value Name "${'$'}STATUS" | sanitize)"
              STATE="${'$'}(status_value State "${'$'}STATUS" | sanitize)"
              STAT_LINE="${'$'}(cat "${'$'}STAT_FILE" 2>/dev/null || true)"
              [ -n "${'$'}STAT_LINE" ] || return

              # /proc/<pid>/stat starts with: pid (comm) state ppid pgrp session ...
              # comm may contain spaces or ')' characters, so remove through the final ') '.
              STAT_REST="${'$'}{STAT_LINE##*) }"
              set -- ${'$'}STAT_REST
              STATE_CODE="${'$'}{1:-?}"
              PPID="${'$'}{2:-0}"
              PGID="${'$'}{3:-0}"
              SID="${'$'}{4:-0}"

              CMDLINE="${'$'}(cat "/proc/${'$'}PID/cmdline" 2>/dev/null | sanitize)"
              [ -n "${'$'}CMDLINE" ] || CMDLINE="[${'$'}NAME]"
              [ -n "${'$'}STATE" ] || STATE="${'$'}STATE_CODE"
              printf 'P|%s|%s|%s|%s|%s|%s|%s\n' \
                "${'$'}PID" "${'$'}PPID" "${'$'}PGID" "${'$'}SID" \
                "${'$'}STATE" "${'$'}NAME" "${'$'}CMDLINE"

              # Linux records children per task. Read every task's children file so a
              # child created by a non-leader thread is not missed. This remains scoped
              # to the active PTY process and its descendants; it never scans all /proc.
              CHILDREN=""
              for CHILDREN_FILE in "/proc/${'$'}PID/task/"*/children; do
                [ -r "${'$'}CHILDREN_FILE" ] || continue
                FILE_CHILDREN="${'$'}(cat "${'$'}CHILDREN_FILE" 2>/dev/null || true)"
                CHILDREN="${'$'}CHILDREN ${'$'}FILE_CHILDREN"
              done
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
