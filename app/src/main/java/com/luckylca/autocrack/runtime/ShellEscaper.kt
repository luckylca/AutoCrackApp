package com.luckylca.autocrack.runtime

object ShellEscaper {
    fun quote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    fun buildHostScript(request: ShellCommandRequest): String = buildString {
        append("umask 077\n")
        append("cd -- ").append(quote(request.workingDirectory)).append(" || exit 125\n")
        request.environment.toSortedMap().forEach { (key, value) ->
            append("export ").append(key).append('=').append(quote(value)).append('\n')
        }
        append("exec /system/bin/sh -c ").append(quote(request.command)).append('\n')
    }
}
