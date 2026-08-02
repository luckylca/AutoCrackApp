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
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "PS1" to "autocrack:\\w# ",
            "AUTOC_WORKSPACE" to "/workspace",
        )
        val innerCommand = buildString {
            append("umask 077\n")
            append("cd -- /workspace || exit 125\n")
            append("exec /bin/bash --noprofile --norc -i")
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
}
