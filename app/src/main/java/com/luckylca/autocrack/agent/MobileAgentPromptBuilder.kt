package com.luckylca.autocrack.agent

object MobileAgentPromptBuilder {
    fun build(
        runtime: MobileAgentRuntimeSession,
        preferences: MobileAgentPreferences = MobileAgentPreferences(),
    ): String {
        val toolpacks = if (runtime.installedToolpacks.isEmpty()) {
            "- 当前没有安装额外工具包"
        } else {
            runtime.installedToolpacks.joinToString("\n") { pack ->
                buildString {
                    append("- ").append(pack.manifest.title).append(' ').append(pack.manifest.version)
                    pack.manifest.description.takeIf(String::isNotBlank)?.let { append(" — ").append(it) }
                    appendLine()
                    pack.manifest.commands.forEach { command ->
                        append("  - ").append(command.name)
                        command.description.takeIf(String::isNotBlank)?.let { append(" — ").append(it) }
                        appendLine()
                    }
                }.trimEnd()
            }
        }
        return """
            你是运行在 Android 手机上的自主 Agent。

            你可以使用以下动作：
            - exec_bash：在当前会话的 Debian rootfs workspace 中执行 Bash。
            - read_file：读取当前会话 workspace 中的文件。
            - write_file：写入当前会话 workspace。
            - kill_process：终止指定进程。

            你可以自由组合当前环境中已经安装的原生命令，也可以自行编写 Bash、Python 或其他脚本完成任务。
            根据用户描述的目标自行调查、执行、验证并继续推进；只有确实缺少无法自行获取的信息时才询问用户。

            当前会话 workspace：${runtime.workspacePath}
            当前已安装工具包：
            $toolpacks

            ${preferences.customSystemPrompt.takeIf(String::isNotBlank)?.let { "用户配置的长期 Agent 指令：\n$it" }.orEmpty()}
        """.trimIndent()
    }
}
