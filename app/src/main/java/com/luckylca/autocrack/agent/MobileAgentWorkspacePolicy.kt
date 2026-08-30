package com.luckylca.autocrack.agent

import java.io.File

internal object MobileAgentWorkspacePolicy {
    private const val ISOLATED_WORKSPACE_MARKER = ".autocrack-session-workspace-v1"

    fun markIsolated(sessionWorkspace: File): File {
        require(sessionWorkspace.isDirectory || sessionWorkspace.mkdirs()) {
            "无法创建会话工作区：${sessionWorkspace.path}"
        }
        val marker = File(sessionWorkspace, ISOLATED_WORKSPACE_MARKER)
        if (!marker.isFile) marker.writeText("1\n", Charsets.UTF_8)
        return sessionWorkspace.canonicalFile
    }

    fun resolve(sessionWorkspace: File, legacyWorkspace: File): File =
        if (File(sessionWorkspace, ISOLATED_WORKSPACE_MARKER).isFile) {
            sessionWorkspace.canonicalFile
        } else {
            legacyWorkspace.canonicalFile
        }
}
