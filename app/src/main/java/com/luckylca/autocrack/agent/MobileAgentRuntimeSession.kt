package com.luckylca.autocrack.agent

import com.luckylca.autocrack.runtime.InstalledToolpack

data class MobileAgentRuntimeSession(
    val tools: AgentToolSession,
    val installedToolpacks: List<InstalledToolpack>,
    val workspacePath: String,
    val cancelAllCommands: () -> Int,
)
