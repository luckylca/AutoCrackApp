package com.luckylca.autocrack.runtime

object ChrootPtyCommandBuilder {
    fun build(rootfsPath: String): String {
        require(rootfsPath.startsWith('/')) { "rootfs 路径必须是绝对路径" }
        require(!rootfsPath.contains("..")) { "rootfs 路径不能包含 .." }

        val environment = linkedMapOf(
            "HOME" to "/root",
            "USER" to "root",
            "LOGNAME" to "root",
            "SHELL" to "/bin/bash",
            "TERM" to "xterm-256color",
            "COLORTERM" to "truecolor",
            "LANG" to "C.UTF-8",
            "LC_ALL" to "C.UTF-8",
            "JAVA_HOME" to JAVA_HOME,
            "PATH" to "$JAVA_HOME/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "AUTOC_WORKSPACE" to "/workspace",
            "AUTOC_ROOTFS_HOST_PATH" to rootfsPath,
        )
        val interactiveShell = buildString {
            append("umask 077\n")
            append(ToolpackSharedEnvironment.shellBootstrap()).append('\n')
            append("cd -- /workspace || exit 125\n")
            append("export PS1=").append(ShellEscaper.quote("autocrack:\\w# ")).append('\n')
            append("exec /bin/bash --noprofile --norc -i")
        }
        val innerCommand = buildString {
            append("if [ -x /usr/bin/script ]; then\n")
            append("  exec /usr/bin/script -q -e -f -c ")
                .append(ShellEscaper.quote(interactiveShell))
                .append(" /dev/null\n")
            append("fi\n")
            append(interactiveShell)
        }

        return buildString {
            append("exec chroot ").append(ShellEscaper.quote(rootfsPath))
            append(" /usr/bin/env -i")
            environment.forEach { (key, value) ->
                append(' ').append(key).append('=').append(ShellEscaper.quote(value))
            }
            append(" /bin/bash --noprofile --norc -c ")
                .append(ShellEscaper.quote(innerCommand))
        }
    }

    private const val JAVA_HOME = "/usr/lib/jvm/java-17-openjdk-arm64"
}
