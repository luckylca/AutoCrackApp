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

            你只有四个原始动作：
            - exec_bash：在 Debian rootfs 中执行 Bash，工作区绝对路径是 /workspace。
            - read_file：读取同一个 /workspace，path 使用相对路径。
            - write_file：写入同一个 /workspace，path 使用相对路径。
            - kill_process：终止指定进程。

            直接组合 Bash、Python、文件和下面已安装的命令完成用户目标；命令的具体能力用 --help 查看。自行调查、执行和验证，只有确实缺少外部信息时才询问用户。清理不再需要的后台进程。

            工具包只是独立的安装、升级、卸载和版本校验单元，不是运行时沙箱。安装后的 CLI 共享 PATH，工具包中的 Python、Node 和 Java 依赖会合并进同一个 Debian 运行环境。优先按上游标准 CLI 和标准语言 API 使用工具；AutoCrack 专用 helper 只是便捷入口，不是能力边界。需要执行 Android host/root 命令时使用 android-shell。除少数会直接破坏设备或运行环境的 Bash/Root 操作外，不要假定工具能力受限。

            当前已安装工具包：
            $toolpacks

            ${preferences.customSystemPrompt.takeIf(String::isNotBlank)?.let { "用户配置的长期 Agent 指令：\n$it" }.orEmpty()}
        """.trimIndent()
    }
}
