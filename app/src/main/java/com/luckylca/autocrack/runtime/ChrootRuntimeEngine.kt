package com.luckylca.autocrack.runtime

import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject

class ChrootRuntimeEngine(
    private val layout: RuntimeLayout,
    private val hostEngine: RootShellRuntimeEngine,
    private val onStage: (String) -> Unit = {},
) : RuntimeEngine {
    override val mode: RuntimeCapabilityMode = RuntimeCapabilityMode.FULL_ROOT

    override suspend fun execute(request: ShellCommandRequest): ShellCommandResult {
        require(layout.readRootfsState() == RuntimeRootfsState.INSTALLED) {
            "Debian rootfs 尚未安装"
        }
        require(layout.rootfsRoot.isDirectory) { "rootfs current 目录不存在" }

        onStage("execute_enter")
        ChrootExecutionGate.acquire(ChrootExecutionKind.ONE_SHOT)
        onStage("gate_acquired")
        val startedAt = System.currentTimeMillis()
        val executionToken = UUID.randomUUID().toString()
        return try {
            onStage("workspace_enter")
            val workspace = layout.createRuntimeWorkspace()
            onStage("workspace_ready")
            onStage("mount_enter")
            val mountResult = performPrepareMounts(workspace)
            onStage("mount_return")
            require(mountResult.succeeded) {
                "准备 chroot 挂载失败：exit=${mountResult.exitCode}, ${mountResult.stderr}"
            }

            onStage("command_build_enter")
            val taggedRequest = request.copy(
                environment = request.environment + (CHROOT_REQUEST_TOKEN_ENV to executionToken),
            )
            onStage("command_execute_enter")
            val hostResult = hostEngine.execute(
                ShellCommandRequest(
                    command = ChrootCommandBuilder.build(
                        rootfsPath = layout.rootfsRoot.path,
                        request = taggedRequest,
                    ),
                    workingDirectory = layout.runtimeRoot.path,
                    stdin = request.stdin,
                    timeoutMillis = request.timeoutMillis,
                    identity = HostExecutionIdentity.ROOT,
                ),
            )
            val orphanCleanup = if (hostResult.timedOut || hostResult.cancelled) {
            onStage("command_execute_return")
                cleanupTaggedProcesses(executionToken)
            } else {
                null
            }
            val cleanupFailure = orphanCleanup
                ?.takeUnless(ShellCommandResult::succeeded)
                ?.let { cleanup ->
                    cleanup.failure
                        ?: cleanup.stderr.takeIf(String::isNotBlank)
                        ?: "chroot 超时进程清理失败：exit=${cleanup.exitCode}"
                }
            val result = hostResult.copy(
                command = request.command,
                workingDirectory = request.workingDirectory,
                failure = listOfNotNull(hostResult.failure, cleanupFailure)
                    .joinToString(" | ")
                    .takeIf(String::isNotBlank),
                auditFilePath = layout.chrootAuditFile.path,
            )
            onStage("append_audit_enter")
            appendAudit(result, startedAt, orphanCleanup)
            onStage("append_audit_return")
            result
        } finally {
            withContext(NonCancellable) {
                try {
                    onStage("cleanup_enter")
                    performCleanupMounts()
                    onStage("cleanup_return")
                } finally {
                    onStage("gate_release_enter")
                    ChrootExecutionGate.release(ChrootExecutionKind.ONE_SHOT)
                    onStage("gate_release_return")
                }
            }
        }
    }

    private suspend fun cleanupTaggedProcesses(executionToken: String): ShellCommandResult =
        hostEngine.execute(
            ShellCommandRequest(
                command = ChrootOrphanCleanupCommandBuilder.build(executionToken),
                workingDirectory = layout.runtimeRoot.path,
                timeoutMillis = ORPHAN_CLEANUP_TIMEOUT_MILLIS,
                identity = HostExecutionIdentity.ROOT,
            ),
        )

    internal suspend fun prepareMountsForPersistentPty(
        workspace: File = layout.createRuntimeWorkspace(),
    ): ShellCommandResult {
        ChrootExecutionGate.requireOwner(ChrootExecutionKind.PERSISTENT_PTY)
        return performPrepareMounts(workspace)
    }

    internal suspend fun cleanupMountsForPersistentPty(): ShellCommandResult {
        ChrootExecutionGate.requireOwner(ChrootExecutionKind.PERSISTENT_PTY)
        return performCleanupMounts()
    }

    suspend fun cleanupMounts(): ShellCommandResult {
        ChrootExecutionGate.requireIdle()
        return performCleanupMounts()
    }

    private suspend fun performPrepareMounts(
        workspace: File = layout.createRuntimeWorkspace(),
    ): ShellCommandResult {
        require(layout.readRootfsState() == RuntimeRootfsState.INSTALLED) {
            "Debian rootfs 尚未安装"
        }
        require(layout.rootfsRoot.isDirectory) { "rootfs current 目录不存在" }
        require(layout.isManagedPath(workspace)) { "工作区不在 AutoCrackApp 管理目录" }
        prepareRootfsDirectories()
        val command = MountScriptBuilder.prepare(
            rootfsPath = layout.rootfsRoot.path,
            workspacePath = workspace.canonicalPath,
            homePath = layout.homeRoot.canonicalPath,
        )
        return hostEngine.execute(
            ShellCommandRequest(
                command = command,
                workingDirectory = layout.runtimeRoot.path,
                timeoutMillis = MOUNT_TIMEOUT_MILLIS,
                outputMode = ShellOutputMode.DISCARD,
            ),
        )
    }

    private suspend fun performCleanupMounts(): ShellCommandResult {
        val command = MountScriptBuilder.cleanup(layout.rootfsRoot.path)
        return hostEngine.execute(
            ShellCommandRequest(
                command = command,
                workingDirectory = layout.runtimeRoot.path,
                timeoutMillis = MOUNT_TIMEOUT_MILLIS,
                outputMode = ShellOutputMode.DISCARD,
            ),
        )
    }

    private fun prepareRootfsDirectories() {
        listOf(
            "dev",
            "dev/pts",
            "proc",
            "sys",
            "workspace",
            "root",
            "tmp",
            "system",
            "vendor",
            "apex",
        ).forEach { relative -> File(layout.rootfsRoot, relative).mkdirs() }
        File(layout.rootfsRoot, "etc/resolv.conf").apply {
            parentFile?.mkdirs()
            writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n", Charsets.UTF_8)
        }
    }

    private suspend fun appendAudit(
        result: ShellCommandResult,
        startedAt: Long,
        orphanCleanup: ShellCommandResult?,
    ) {
        withContext(Dispatchers.IO) {
            val json = JSONObject()
                .put("schemaVersion", 1)
                .put("requestId", result.requestId)
                .put("runtime", "debian-chroot")
                .put("rootfsVersion", layout.readRootfsVersion() ?: JSONObject.NULL)
                .put("command", result.command)
                .put("workingDirectory", result.workingDirectory)
                .put("exitCode", result.exitCode ?: JSONObject.NULL)
                .put("startedAtEpochMillis", startedAt)
                .put("completedAtEpochMillis", result.completedAtEpochMillis)
                .put("durationMillis", result.durationMillis)
                .put("timedOut", result.timedOut)
                .put("cancelled", result.cancelled)
                .put("stdoutChars", result.stdout.length)
                .put("stderrChars", result.stderr.length)
                .put("stdoutTruncated", result.stdoutTruncated)
                .put("stderrTruncated", result.stderrTruncated)
                .put("failure", result.failure ?: JSONObject.NULL)
                .put("orphanCleanupAttempted", orphanCleanup != null)
                .put("orphanCleanupSucceeded", orphanCleanup?.succeeded ?: JSONObject.NULL)
                .put("orphanCleanupExitCode", orphanCleanup?.exitCode ?: JSONObject.NULL)
            layout.auditRoot.mkdirs()
            synchronized(AUDIT_LOCK) {
                layout.chrootAuditFile.appendText(json.toString() + "\n", Charsets.UTF_8)
            }
        }
    }

    private companion object {
        val AUDIT_LOCK = Any()
        const val MOUNT_TIMEOUT_MILLIS = 30_000L
        const val ORPHAN_CLEANUP_TIMEOUT_MILLIS = 5_000L
        const val CHROOT_REQUEST_TOKEN_ENV = "AUTOC_CHROOT_REQUEST_TOKEN"
    }
}

internal object ChrootOrphanCleanupCommandBuilder {
    private val TOKEN_REGEX = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    fun build(executionToken: String): String {
        require(TOKEN_REGEX.matches(executionToken)) { "非法 chroot request token" }
        val marker = ShellEscaper.quote("AUTOC_CHROOT_REQUEST_TOKEN=$executionToken")
        return """
            set -eu
            MARKER=$marker
            find_tagged_pids() {
              grep -a -l -F -- "${'$'}MARKER" /proc/[0-9]*/environ 2>/dev/null |
                while IFS= read -r ENV_FILE; do
                  PID=${'$'}{ENV_FILE#/proc/}
                  PID=${'$'}{PID%/environ}
                  case "${'$'}PID" in ''|*[!0-9]*) continue ;; esac
                  [ "${'$'}PID" -gt 1 ] && printf '%s\n' "${'$'}PID"
                done
            }

            PIDS=${'$'}(find_tagged_pids | sort -nr -u)
            for PID in ${'$'}PIDS; do kill -TERM "${'$'}PID" 2>/dev/null || true; done
            [ -z "${'$'}PIDS" ] || sleep 1

            REMAINING=${'$'}(find_tagged_pids | sort -nr -u)
            for PID in ${'$'}REMAINING; do kill -KILL "${'$'}PID" 2>/dev/null || true; done
            [ -z "${'$'}REMAINING" ] || sleep 1

            FINAL=${'$'}(find_tagged_pids | sort -nr -u)
            [ -z "${'$'}FINAL" ] || {
              printf 'CHROOT_ORPHAN_CLEANUP_REMAINING=%s\n' "${'$'}FINAL" >&2
              exit 1
            }
            printf 'CHROOT_ORPHAN_CLEANUP_OK term=%s kill=%s\n' \
              "${'$'}(printf '%s\n' "${'$'}PIDS" | awk 'NF {n++} END {print n+0}')" \
              "${'$'}(printf '%s\n' "${'$'}REMAINING" | awk 'NF {n++} END {print n+0}')"
        """.trimIndent()
    }
}

object ChrootCommandBuilder {
    fun build(rootfsPath: String, request: ShellCommandRequest): String {
        require(request.workingDirectory.startsWith('/')) {
            "chroot 工作目录必须是绝对路径"
        }
        require(!request.workingDirectory.contains("..")) {
            "chroot 工作目录不能包含 .."
        }
        val environment = linkedMapOf(
            "HOME" to "/root",
            "USER" to "root",
            "LOGNAME" to "root",
            "SHELL" to "/bin/bash",
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
            "LC_ALL" to "C.UTF-8",
            "JAVA_HOME" to "/usr/lib/jvm/java-17-openjdk-arm64",
            "PATH" to "/usr/lib/jvm/java-17-openjdk-arm64/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "AUTOC_WORKSPACE" to "/workspace",
        ).apply { putAll(request.environment) }

        val inner = buildString {
            append("cd -- ").append(ShellEscaper.quote(request.workingDirectory))
                .append(" || exit 125\n")
            append("exec /bin/bash --noprofile --norc -lc ")
                .append(ShellEscaper.quote(request.command))
        }
        return buildString {
            append("exec chroot ").append(ShellEscaper.quote(rootfsPath))
            append(" /usr/bin/env -i")
            environment.forEach { (key, value) ->
                append(' ').append(key).append('=').append(ShellEscaper.quote(value))
            }
            append(" /bin/bash --noprofile --norc -c ").append(ShellEscaper.quote(inner))
        }
    }
}

object MountScriptBuilder {
    fun prepare(rootfsPath: String, workspacePath: String, homePath: String): String {
        val root = ShellEscaper.quote(rootfsPath)
        val workspace = ShellEscaper.quote(workspacePath)
        val home = ShellEscaper.quote(homePath)
        return """
            set -eu
            ROOT=$root
            WORKSPACE=$workspace
            HOME_SOURCE=$home
            is_mounted() { grep -qs " ${'$'}1 " /proc/mounts; }
            bind_once() {
              SRC="${'$'}1"; DST="${'$'}2"
              if ! is_mounted "${'$'}DST"; then mount --bind "${'$'}SRC" "${'$'}DST"; fi
            }
            rbind_once() {
              SRC="${'$'}1"; DST="${'$'}2"
              if ! is_mounted "${'$'}DST"; then
                mount --rbind "${'$'}SRC" "${'$'}DST" 2>/dev/null || mount --bind "${'$'}SRC" "${'$'}DST"
              fi
            }
            mkdir -p "${'$'}ROOT/dev" "${'$'}ROOT/dev/pts" "${'$'}ROOT/proc" "${'$'}ROOT/sys" \
              "${'$'}ROOT/workspace" "${'$'}ROOT/root" "${'$'}ROOT/system" "${'$'}ROOT/vendor" "${'$'}ROOT/apex"
            rbind_once /dev "${'$'}ROOT/dev"
            if ! is_mounted "${'$'}ROOT/proc"; then mount -t proc proc "${'$'}ROOT/proc"; fi
            rbind_once /sys "${'$'}ROOT/sys"
            bind_once "${'$'}WORKSPACE" "${'$'}ROOT/workspace"
            bind_once "${'$'}HOME_SOURCE" "${'$'}ROOT/root"
            [ ! -d /system ] || bind_once /system "${'$'}ROOT/system"
            [ ! -d /vendor ] || bind_once /vendor "${'$'}ROOT/vendor"
            [ ! -d /apex ] || bind_once /apex "${'$'}ROOT/apex"
            echo ROOTFS_MOUNTS_READY
        """.trimIndent()
    }

    fun cleanup(rootfsPath: String): String {
        val root = ShellEscaper.quote(rootfsPath)
        return """
            set -eu
            ROOT=$root
            PASS=0
            while [ "${'$'}PASS" -lt 8 ]; do
              TARGETS=
              while read -r SOURCE TARGET FSTYPE OPTIONS REST; do
                case "${'$'}TARGET" in
                  "${'$'}ROOT"/*)
                    TARGETS="${'$'}TARGET
            ${'$'}TARGETS"
                    ;;
                esac
              done < /proc/mounts

              [ -n "${'$'}TARGETS" ] || break
              PROGRESS=0
              OLD_IFS=${'$'}IFS
              IFS='
            '
              for TARGET in ${'$'}TARGETS; do
                [ -n "${'$'}TARGET" ] || continue
                if umount -l "${'$'}TARGET" 2>/dev/null; then
                  PROGRESS=1
                fi
              done
              IFS=${'$'}OLD_IFS
              PASS=${'$'}((PASS + 1))
              [ "${'$'}PROGRESS" -eq 1 ] || break
            done

            REMAINING=
            while read -r SOURCE TARGET FSTYPE OPTIONS REST; do
              case "${'$'}TARGET" in
                "${'$'}ROOT"/*)
                  REMAINING="${'$'}REMAINING${'$'}TARGET
            "
                  ;;
              esac
            done < /proc/mounts

            if [ -n "${'$'}REMAINING" ]; then
              printf 'ROOTFS_MOUNTS_REMAIN\n%s' "${'$'}REMAINING"
              exit 1
            fi
            echo ROOTFS_MOUNTS_CLEAN
        """.trimIndent()
    }
}
