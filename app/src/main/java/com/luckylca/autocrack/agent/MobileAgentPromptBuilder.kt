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
                    if ("SKILL.md" in pack.manifest.requiredPaths) {
                        append("  - skill: /opt/autocrack/toolpacks/active/")
                            .append(pack.manifest.id)
                            .append("/SKILL.md")
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
            - Keep the harness simple: exec_bash is the main capability. Installed toolpacks are ordinary shell commands on PATH, not separate model tools.
            - Use read_file and write_file for file contents under /workspace; use exec_bash for shell discovery, pipelines, scripts, rg/grep/find, and CLI composition.
            - Before first using a non-trivial installed CLI for a task, inspect its --help. If its entry above exposes a skill path, read that SKILL.md when the task matches the toolpack.
            - Use android-shell from Bash when a command must run on the Android host rather than inside Debian.
            - Prefer machine-readable --json output when a CLI supports it. Check ok, supported, error/reason, handles, and tokens instead of assuming empty output means success.
            - For the shared AutoCrack Runtime CLI family, use runtime-inspect doctor --package <target> --json when runtime readiness is unknown or after a bridge/runtime failure. Do not change LSPosed configuration merely because a heartbeat is absent.
            - Treat returned obj_* handles and asynchronous tokens as opaque values. Reuse the exact value and follow the CLI's corresponding result/release command when required.
            - Installed toolpacks share the same runtime environment; prefer their standard CLI and language APIs and compose them through Bash.
            - If a command fails, read the structured error, inspect --help/SKILL.md, diagnose the dependency, and choose a supported fallback rather than fabricating success.
            - Clean up background processes you start when they are no longer needed.
            - Be concise in your responses and show file paths clearly when working with files.

            ${preferences.customSystemPrompt.takeIf(String::isNotBlank)?.let { "Additional persistent user instructions:\n$it" }.orEmpty()}

            Current working directory: /workspace
        """.trimIndent()
    }
}
