package com.luckylca.autocrack.runtime

import android.content.Context
import java.io.File
import org.json.JSONObject

class RuntimeLayout(context: Context) {
    val filesRoot: File = context.applicationContext.filesDir.canonicalFile
    val runtimeRoot: File = File(filesRoot, "runtime").canonicalFile
    val rootfsContainer: File = File(runtimeRoot, "rootfs").canonicalFile
    val rootfsRoot: File = File(rootfsContainer, "current").canonicalFile
    val rootfsStagingRoot: File = File(rootfsContainer, "staging").canonicalFile
    val rootfsBackupRoot: File = File(rootfsContainer, "backup").canonicalFile
    val rootfsPackagesRoot: File = File(rootfsContainer, "packages").canonicalFile
    val homeRoot: File = File(runtimeRoot, "home").canonicalFile
    val binRoot: File = File(runtimeRoot, "bin").canonicalFile
    val toolpacksRoot: File = File(runtimeRoot, "toolpacks").canonicalFile
    val sessionsRoot: File = File(runtimeRoot, "sessions").canonicalFile
    val auditRoot: File = File(runtimeRoot, "audit").canonicalFile
    val quarantineRoot: File = File(runtimeRoot, "quarantine").canonicalFile
    val tempRoot: File = File(runtimeRoot, "tmp").canonicalFile
    val workspacesRoot: File = File(filesRoot, "workspaces").canonicalFile
    val runtimeStateFile: File = File(runtimeRoot, "runtime-state.json").canonicalFile
    val shellAuditFile: File = File(auditRoot, "shell-exec.jsonl").canonicalFile
    val chrootAuditFile: File = File(auditRoot, "chroot-exec.jsonl").canonicalFile
    val installedRootfsManifestFile: File =
        File(rootfsContainer, "installed-manifest.json").canonicalFile

    fun initialize(): RuntimeLayout {
        listOf(
            runtimeRoot,
            rootfsContainer,
            rootfsPackagesRoot,
            homeRoot,
            binRoot,
            toolpacksRoot,
            sessionsRoot,
            auditRoot,
            quarantineRoot,
            tempRoot,
            workspacesRoot,
        ).forEach(::ensureDirectory)

        if (!runtimeStateFile.exists()) {
            writeRuntimeState(RuntimeRootfsState.NOT_INSTALLED, null)
        }
        return this
    }

    fun createRuntimeWorkspace(): File {
        initialize()
        val directory = File(workspacesRoot, "runtime-foundation").canonicalFile
        ensureInside(workspacesRoot, directory)
        ensureDirectory(directory)
        return directory
    }

    fun createAgentWorkspace(sessionId: String): File {
        require(sessionId.matches(Regex("^[A-Za-z0-9._-]{1,96}$"))) { "非法 Agent session id" }
        initialize()
        val agentRoot = File(workspacesRoot, "agent").canonicalFile
        ensureInside(workspacesRoot, agentRoot)
        ensureDirectory(agentRoot)
        val directory = File(agentRoot, sessionId).canonicalFile
        ensureInside(agentRoot, directory)
        ensureDirectory(directory)
        return directory
    }

    fun readRootfsState(): RuntimeRootfsState {
        initialize()
        return runCatching {
            val value = JSONObject(runtimeStateFile.readText(Charsets.UTF_8))
                .optString("rootfsState", RuntimeRootfsState.NOT_INSTALLED.name)
            RuntimeRootfsState.valueOf(value)
        }.getOrDefault(RuntimeRootfsState.BROKEN)
    }

    fun readRootfsVersion(): String? {
        initialize()
        return runCatching {
            JSONObject(runtimeStateFile.readText(Charsets.UTF_8))
                .optString("rootfsVersion")
                .takeIf(String::isNotBlank)
        }.getOrNull()
    }

    fun updateRootfsState(state: RuntimeRootfsState, version: String? = null) {
        initialize()
        writeRuntimeState(state, version)
    }

    fun requireManagedPath(path: File): File {
        val canonical = path.canonicalFile
        val allowed = listOf(runtimeRoot, workspacesRoot).any { root -> isInside(root, canonical) }
        require(allowed) { "路径不在 AutoCrackApp 管理目录中：${canonical.path}" }
        return canonical
    }

    fun isManagedPath(path: File): Boolean {
        val canonical = path.canonicalFile
        return listOf(runtimeRoot, workspacesRoot).any { root -> isInside(root, canonical) }
    }

    private fun writeRuntimeState(state: RuntimeRootfsState, version: String?) {
        val json = JSONObject()
            .put("schemaVersion", 2)
            .put("rootfsState", state.name)
            .put("rootfsVersion", version ?: JSONObject.NULL)
            .put("rootfsPath", rootfsRoot.path)
            .put("updatedAtEpochMillis", System.currentTimeMillis())
        runtimeStateFile.writeText(json.toString(2), Charsets.UTF_8)
    }

    private fun ensureDirectory(directory: File) {
        require(isInside(filesRoot, directory) || directory == filesRoot) {
            "拒绝初始化应用私有目录之外的路径：${directory.path}"
        }
        if (directory.exists()) {
            require(directory.isDirectory) { "目标不是目录：${directory.path}" }
        } else {
            check(directory.mkdirs()) { "无法创建目录：${directory.path}" }
        }
    }

    private fun ensureInside(root: File, child: File) {
        require(isInside(root, child)) { "路径越界：${child.path}" }
    }

    private fun isInside(root: File, child: File): Boolean {
        val rootPath = root.canonicalPath
        val childPath = child.canonicalPath
        return childPath == rootPath || childPath.startsWith("$rootPath${File.separator}")
    }
}
