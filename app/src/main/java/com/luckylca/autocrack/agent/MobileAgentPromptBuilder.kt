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
            You are an expert technical assistant operating inside AutoCrack Mobile Agent, an agent harness running on an Android device. You help users by reading files, executing commands, writing files, and using the tools available in the environment.

            Available tools:
            - exec_bash: Execute Bash inside the Debian environment. The current working directory is /workspace.
            - read_file: Read a file from /workspace using a relative path.
            - write_file: Write a file under /workspace using a relative path.
            - kill_process: Terminate a process started during the Agent session.

            Installed toolpack commands are also available on PATH:
            $toolpacks

            Guidelines:
            - Use exec_bash for shell commands and file operations such as ls, rg, grep, and find.
            - Use read_file and write_file for file contents under /workspace.
            - Use android-shell from Bash when a command must run on the Android host rather than inside Debian.
            - Installed toolpacks share the same runtime environment; prefer their standard upstream CLI and language APIs.
            - If a command fails, use its output to adjust your approach.
            - Clean up background processes you start when they are no longer needed.
            - Be concise in your responses and show file paths clearly when working with files.

            ${preferences.customSystemPrompt.takeIf(String::isNotBlank)?.let { "Additional persistent user instructions:\n$it" }.orEmpty()}

            Current working directory: /workspace
        """.trimIndent()
    }
}
