package com.luckylca.autocrack.runtime

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject

class ChrootRuntimeEngine(
    private val layout: RuntimeLayout,
    private val hostEngine: RootShellRuntimeEngine,
) : RuntimeEngine {
    override val mode: RuntimeCapabilityMode = RuntimeCapabilityMode.FULL_ROOT

    override suspend fun execute(request: ShellCommandRequest): ShellCommandResult {
        require(layout.readRootfsState() == RuntimeRootfsState.INSTALLED) {
            "Debian rootfs 尚未安装"
        }
        require(layout.rootfsRoot.isDirectory) { "rootfs current 目录不存在" }

        ChrootExecutionGate.acquire(ChrootExecutionKind.ONE_SHOT)
        val startedAt = System.currentTimeMillis()
        return try {
            val workspace = layout.createRuntimeWorkspace()
            val mountResult = performPrepareMounts(workspace)
            require(mountResult.succeeded) {
                "准备 chroot 挂载失败：exit=${mountResult.exitCode}, ${mountResult.stderr}"
            }

            val hostResult = hostEngine.execute(
                ShellCommandRequest(
                    command = ChrootCommandBuilder.build(
                        rootfsPath = layout.rootfsRoot.path,
                        request = request,
                    ),
                    workingDirectory = layout.runtimeRoot.path,
                    stdin = request.stdin,
                    timeoutMillis = request.timeoutMillis,
                    identity = HostExecutionIdentity.ROOT,
                ),
            )
            val result = hostResult.copy(
                command = request.command,
                workingDirectory = request.workingDirectory,
                auditFilePath = layout.chrootAuditFile.path,
            )
            appendAudit(result, startedAt)
            result
        } finally {
            withContext(NonCancellable) {
                try {
                    performCleanupMounts()
                } finally {
                    ChrootExecutionGate.release(ChrootExecutionKind.ONE_SHOT)
                }
            }
        }
    }

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

    private suspend fun appendAudit(result: ShellCommandResult, startedAt: Long) {
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
            layout.auditRoot.mkdirs()
            synchronized(AUDIT_LOCK) {
                layout.chrootAuditFile.appendText(json.toString() + "\n", Charsets.UTF_8)
            }
        }
    }

    private companion object {
        val AUDIT_LOCK = Any()
        const val MOUNT_TIMEOUT_MILLIS = 30_000L
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
