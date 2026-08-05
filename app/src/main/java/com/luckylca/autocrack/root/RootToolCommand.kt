package com.luckylca.autocrack.root

import com.luckylca.autocrack.apk.PackageOutputParser

sealed interface RootToolCommand {
    val label: String
    val timeoutMillis: Long

    data class ListInstalledPackages(
        val androidUserId: Int,
    ) : RootToolCommand {
        override val label: String = "List installed packages for user $androidUserId"
        override val timeoutMillis: Long = 20_000L
    }

    data class ReadPackageApkPaths(
        val packageName: String,
        val androidUserId: Int,
    ) : RootToolCommand {
        override val label: String = "Read APK paths for $packageName"
        override val timeoutMillis: Long = 10_000L
    }

    data class CopyApkToWorkspace(
        val sourcePath: String,
        val destinationPath: String,
        val ownerUid: Int,
        val ownerGid: Int,
    ) : RootToolCommand {
        override val label: String = "Copy ${sourcePath.substringAfterLast('/')} to workspace"
        override val timeoutMillis: Long = 120_000L
    }

    data class ListHostProcesses(
        val filter: String = "",
        val maxCount: Int = 512,
    ) : RootToolCommand {
        override val label: String = "List host processes"
        override val timeoutMillis: Long = 15_000L
    }

    data class ReadProcessIdentity(
        val pid: Int,
    ) : RootToolCommand {
        override val label: String = "Read identity for PID $pid"
        override val timeoutMillis: Long = 8_000L
    }

    data class ReadProcessAttachPreflight(
        val pid: Int,
    ) : RootToolCommand {
        override val label: String = "Read attach preflight for PID $pid"
        override val timeoutMillis: Long = 8_000L
    }

    data class ReadProcessMaps(
        val pid: Int,
    ) : RootToolCommand {
        override val label: String = "Read maps for PID $pid"
        override val timeoutMillis: Long = 12_000L
    }

    data class ListProcessThreads(
        val pid: Int,
        val maxCount: Int = 1024,
    ) : RootToolCommand {
        override val label: String = "List threads for PID $pid"
        override val timeoutMillis: Long = 12_000L
    }

    data class ListProcessFileDescriptors(
        val pid: Int,
        val maxCount: Int = 512,
    ) : RootToolCommand {
        override val label: String = "List file descriptors for PID $pid"
        override val timeoutMillis: Long = 12_000L
    }
}

class RootToolExecutor(
    private val runner: RootCommandRunner,
    private val suPath: String,
) {
    init {
        require(suPath.isNotBlank()) { "su path must not be blank" }
        require('\u0000' !in suPath && '\n' !in suPath && '\r' !in suPath) {
            "su path contains an invalid character"
        }
    }

    suspend fun execute(command: RootToolCommand): CommandResult = runner.run(
        command = RootToolCommandFactory.build(suPath, command),
        label = command.label,
        timeoutMillis = command.timeoutMillis,
    )
}

object RootToolCommandFactory {
    fun build(suPath: String, command: RootToolCommand): List<String> {
        require(suPath.isNotBlank()) { "su path must not be blank" }
        val shellCommand = when (command) {
            is RootToolCommand.ListInstalledPackages -> {
                require(command.androidUserId >= 0) { "Android user id must not be negative" }
                "pm list packages -f -U --user ${command.androidUserId}"
            }

            is RootToolCommand.ReadPackageApkPaths -> {
                PackageOutputParser.requireValidPackageName(command.packageName)
                require(command.androidUserId >= 0) { "Android user id must not be negative" }
                "pm path --user ${command.androidUserId} ${command.packageName}"
            }

            is RootToolCommand.CopyApkToWorkspace -> buildCopyCommand(command)
            is RootToolCommand.ListHostProcesses -> buildListHostProcessesCommand(command)
            is RootToolCommand.ReadProcessIdentity -> buildReadProcessIdentityCommand(command.pid)
            is RootToolCommand.ReadProcessAttachPreflight -> buildAttachPreflightCommand(command.pid)
            is RootToolCommand.ReadProcessMaps -> buildReadProcessMapsCommand(command.pid)
            is RootToolCommand.ListProcessThreads -> buildListThreadsCommand(command)
            is RootToolCommand.ListProcessFileDescriptors -> buildListFileDescriptorsCommand(command)
        }
        return listOf(suPath, "-c", shellCommand)
    }

    fun shellQuote(value: String): String {
        require('\u0000' !in value && '\n' !in value && '\r' !in value) {
            "Shell argument contains an invalid control character"
        }
        return "'${value.replace("'", "'\"'\"'")}'"
    }

    private fun buildCopyCommand(command: RootToolCommand.CopyApkToWorkspace): String {
        require(command.sourcePath.startsWith('/')) { "APK source path must be absolute" }
        require(command.sourcePath.endsWith(".apk", ignoreCase = true)) {
            "APK source path must end with .apk"
        }
        require(command.destinationPath.startsWith('/')) { "Destination path must be absolute" }
        require(command.destinationPath.endsWith(".apk", ignoreCase = true)) {
            "Destination path must end with .apk"
        }
        require(command.ownerUid >= 0 && command.ownerGid >= 0) {
            "Workspace owner ids must not be negative"
        }

        val source = shellQuote(command.sourcePath)
        val destination = shellQuote(command.destinationPath)
        return buildString {
            append("umask 077; cp -- ")
            append(source)
            append(' ')
            append(destination)
            append(" && chown ")
            append(command.ownerUid)
            append(':')
            append(command.ownerGid)
            append(' ')
            append(destination)
            append(" && chmod 600 ")
            append(destination)
        }
    }

    private fun buildListHostProcessesCommand(command: RootToolCommand.ListHostProcesses): String {
        require(command.maxCount in 1..MAX_PROCESS_COUNT) {
            "Process count must be between 1 and $MAX_PROCESS_COUNT"
        }
        val filter = shellQuote(command.filter.trim())
        return """
            filter=$filter
            max_count=${command.maxCount}
            count=0
            printf 'pid\tppid\tuid\tstate\tname\tcmdline\n'
            for proc in /proc/[0-9]*; do
              [ -d "${'$'}proc" ] || continue
              pid=${'$'}{proc##*/}
              status=${'$'}(cat "${'$'}proc/status" 2>/dev/null) || continue
              name=${'$'}(printf '%s\n' "${'$'}status" | awk -F '\t' '${'$'}1 == "Name:" { print ${'$'}2; exit }')
              state=${'$'}(printf '%s\n' "${'$'}status" | awk -F '\t' '${'$'}1 == "State:" { print ${'$'}2; exit }')
              ppid=${'$'}(printf '%s\n' "${'$'}status" | awk -F '\t' '${'$'}1 == "PPid:" { print ${'$'}2; exit }')
              uid=${'$'}(printf '%s\n' "${'$'}status" | awk -F '[\t ]+' '${'$'}1 == "Uid:" { print ${'$'}2; exit }')
              cmdline=${'$'}(tr '\000' ' ' < "${'$'}proc/cmdline" 2>/dev/null | tr '\t\r\n' '   ')
              if [ -n "${'$'}filter" ]; then
                case "${'$'}name ${'$'}cmdline" in
                  *"${'$'}filter"*) ;;
                  *) continue ;;
                esac
              fi
              printf '%s\t%s\t%s\t%s\t%s\t%s\n' "${'$'}pid" "${'$'}ppid" "${'$'}uid" "${'$'}state" "${'$'}name" "${'$'}cmdline"
              count=${'$'}((count + 1))
              [ "${'$'}count" -ge "${'$'}max_count" ] && break
            done
        """.trimIndent()
    }

    private fun buildReadProcessIdentityCommand(pid: Int): String {
        requireValidPid(pid)
        return """
            proc=/proc/$pid
            [ -d "${'$'}proc" ] || { echo 'PROCESS_NOT_FOUND pid=$pid' >&2; exit 3; }
            printf 'pid=$pid\n'
            printf 'cmdline='; tr '\000' ' ' < "${'$'}proc/cmdline" 2>/dev/null || true; printf '\n'
            printf 'exe='; readlink "${'$'}proc/exe" 2>/dev/null || true
            printf 'cwd='; readlink "${'$'}proc/cwd" 2>/dev/null || true
            printf 'root='; readlink "${'$'}proc/root" 2>/dev/null || true
            printf 'selinux='; cat "${'$'}proc/attr/current" 2>/dev/null || true
            grep -E '^(Name|State|Tgid|Pid|PPid|TracerPid|Uid|Gid|FDSize|Groups|Threads|NoNewPrivs|Seccomp|Seccomp_filters):' "${'$'}proc/status" 2>/dev/null || true
        """.trimIndent()
    }

    private fun buildAttachPreflightCommand(pid: Int): String {
        requireValidPid(pid)
        return """
            proc=/proc/$pid
            [ -d "${'$'}proc" ] || { echo 'PROCESS_NOT_FOUND pid=$pid' >&2; exit 3; }
            printf 'pid=$pid\n'
            printf 'process_exists=true\n'
            if [ -r "${'$'}proc/maps" ]; then echo 'maps_readable=true'; else echo 'maps_readable=false'; fi
            if [ -r "${'$'}proc/mem" ]; then echo 'mem_readable=true'; else echo 'mem_readable=false'; fi
            if [ -d "${'$'}proc/task" ]; then echo 'tasks_readable=true'; else echo 'tasks_readable=false'; fi
            printf 'selinux='; cat "${'$'}proc/attr/current" 2>/dev/null || true
            printf 'ptrace_scope='; cat /proc/sys/kernel/yama/ptrace_scope 2>/dev/null || echo unavailable
            grep -E '^(Name|State|Uid|Gid|TracerPid|Threads|NoNewPrivs|Seccomp|Seccomp_filters):' "${'$'}proc/status" 2>/dev/null || true
            echo 'attach_attempted=false'
            echo 'state_changed=false'
        """.trimIndent()
    }

    private fun buildReadProcessMapsCommand(pid: Int): String {
        requireValidPid(pid)
        return """
            maps=/proc/$pid/maps
            [ -r "${'$'}maps" ] || { echo 'MAPS_NOT_READABLE pid=$pid' >&2; exit 4; }
            cat "${'$'}maps"
        """.trimIndent()
    }

    private fun buildListThreadsCommand(command: RootToolCommand.ListProcessThreads): String {
        requireValidPid(command.pid)
        require(command.maxCount in 1..MAX_THREAD_COUNT) {
            "Thread count must be between 1 and $MAX_THREAD_COUNT"
        }
        return """
            task_root=/proc/${command.pid}/task
            [ -d "${'$'}task_root" ] || { echo 'TASKS_NOT_READABLE pid=${command.pid}' >&2; exit 4; }
            max_count=${command.maxCount}
            count=0
            printf 'tid\tstate\tname\n'
            for task in "${'$'}task_root"/[0-9]*; do
              [ -d "${'$'}task" ] || continue
              tid=${'$'}{task##*/}
              name=${'$'}(cat "${'$'}task/comm" 2>/dev/null | tr '\t\r\n' '   ')
              state=${'$'}(awk -F '\t' '${'$'}1 == "State:" { print ${'$'}2; exit }' "${'$'}task/status" 2>/dev/null)
              printf '%s\t%s\t%s\n' "${'$'}tid" "${'$'}state" "${'$'}name"
              count=${'$'}((count + 1))
              [ "${'$'}count" -ge "${'$'}max_count" ] && break
            done
        """.trimIndent()
    }

    private fun buildListFileDescriptorsCommand(
        command: RootToolCommand.ListProcessFileDescriptors,
    ): String {
        requireValidPid(command.pid)
        require(command.maxCount in 1..MAX_FD_COUNT) {
            "File descriptor count must be between 1 and $MAX_FD_COUNT"
        }
        return """
            fd_root=/proc/${command.pid}/fd
            [ -d "${'$'}fd_root" ] || { echo 'FDS_NOT_READABLE pid=${command.pid}' >&2; exit 4; }
            max_count=${command.maxCount}
            count=0
            printf 'fd\ttarget\n'
            for fd_path in "${'$'}fd_root"/[0-9]*; do
              [ -e "${'$'}fd_path" ] || [ -L "${'$'}fd_path" ] || continue
              fd=${'$'}{fd_path##*/}
              target=${'$'}(readlink "${'$'}fd_path" 2>/dev/null | tr '\t\r\n' '   ')
              printf '%s\t%s\n' "${'$'}fd" "${'$'}target"
              count=${'$'}((count + 1))
              [ "${'$'}count" -ge "${'$'}max_count" ] && break
            done
        """.trimIndent()
    }

    private fun requireValidPid(pid: Int) {
        require(pid in 1..Int.MAX_VALUE) { "PID must be positive" }
    }

    private const val MAX_PROCESS_COUNT = 2_000
    private const val MAX_THREAD_COUNT = 8_192
    private const val MAX_FD_COUNT = 4_096
}
