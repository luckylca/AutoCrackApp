package com.luckylca.autocrack.apk

import android.content.Context
import android.os.Process
import com.luckylca.autocrack.root.CommandResult
import com.luckylca.autocrack.root.RootCommandRunner
import com.luckylca.autocrack.root.RootStatus
import com.luckylca.autocrack.root.RootToolCommand
import com.luckylca.autocrack.root.RootToolExecutor
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PackageRepository(
    context: Context,
    private val runner: RootCommandRunner,
) {
    private val applicationContext = context.applicationContext
    private val workspaceRoot = File(applicationContext.filesDir, WORKSPACES_DIRECTORY)

    suspend fun listInstalledApps(rootStatus: RootStatus): List<InstalledApp> =
        withContext(Dispatchers.IO) {
            val result = executor(rootStatus).execute(
                RootToolCommand.ListInstalledPackages(androidUserId()),
            )
            val output = result.requireSuccessfulOutput("读取已安装应用列表")
            PackageOutputParser.parseInstalledPackages(output).also { apps ->
                if (apps.isEmpty()) {
                    throw PackageOperationException("pm 没有返回任何可解析的已安装应用")
                }
            }
        }

    suspend fun readApkSources(
        rootStatus: RootStatus,
        packageName: String,
    ): List<ApkSource> = withContext(Dispatchers.IO) {
        PackageOutputParser.requireValidPackageName(packageName)
        val result = executor(rootStatus).execute(
            RootToolCommand.ReadPackageApkPaths(
                packageName = packageName,
                androidUserId = androidUserId(),
            ),
        )
        val output = result.requireSuccessfulOutput("读取 $packageName 的 APK 路径")
        PackageOutputParser.parseApkSources(output).also { sources ->
            if (sources.isEmpty()) {
                throw PackageOperationException("$packageName 没有返回 base.apk 或 Split APK 路径")
            }
        }
    }

    suspend fun extractPackage(
        rootStatus: RootStatus,
        packageName: String,
    ): ExtractionReport = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val sources = readApkSources(rootStatus, packageName)
        val workspace = createWorkspace(packageName)
        val rootExecutor = executor(rootStatus)

        try {
            val extracted = sources.map { source ->
                val destination = checkedDestination(workspace, source.fileName)
                if (destination.exists() && !destination.delete()) {
                    throw PackageOperationException("无法清理旧文件：${destination.absolutePath}")
                }

                val result = rootExecutor.execute(
                    RootToolCommand.CopyApkToWorkspace(
                        sourcePath = source.sourcePath,
                        destinationPath = destination.absolutePath,
                        ownerUid = Process.myUid(),
                        ownerGid = Process.myUid(),
                    ),
                )
                result.requireSuccessfulOutput("提取 ${source.fileName}", allowBlankOutput = true)

                if (!destination.isFile || destination.length() <= 0L) {
                    throw PackageOperationException("提取完成后文件不存在或为空：${source.fileName}")
                }

                ExtractedApk(
                    sourcePath = source.sourcePath,
                    localPath = destination.canonicalPath,
                    fileName = source.fileName,
                    kind = source.kind,
                    sizeBytes = destination.length(),
                    sha256 = sha256(destination),
                )
            }

            ExtractionReport(
                packageName = packageName,
                workspacePath = workspace.canonicalPath,
                artifacts = extracted,
                startedAtEpochMillis = startedAt,
                completedAtEpochMillis = System.currentTimeMillis(),
            )
        } catch (exception: Exception) {
            workspace.deleteRecursively()
            if (exception is PackageOperationException) throw exception
            throw PackageOperationException("提取 $packageName 时发生错误：${exception.message}", exception)
        }
    }

    private fun executor(rootStatus: RootStatus): RootToolExecutor {
        if (!rootStatus.isRootGranted) {
            throw PackageOperationException("Root 尚未授权，不能读取或提取 APK")
        }
        val suPath = rootStatus.suPath
            ?: throw PackageOperationException("Root 已授权，但没有可用的 su 路径")
        return RootToolExecutor(runner, suPath)
    }

    private fun createWorkspace(packageName: String): File {
        PackageOutputParser.requireValidPackageName(packageName)
        val packageDirectory = File(workspaceRoot, packageName)
        if (!packageDirectory.exists() && !packageDirectory.mkdirs()) {
            throw PackageOperationException("无法创建应用工作目录：${packageDirectory.absolutePath}")
        }

        var attempt = 0
        while (attempt < MAX_WORKSPACE_ATTEMPTS) {
            val suffix = if (attempt == 0) "" else "-$attempt"
            val candidate = File(packageDirectory, "session-${System.currentTimeMillis()}$suffix")
            if (candidate.mkdir()) {
                ensureInsideWorkspaceRoot(candidate)
                return candidate
            }
            attempt += 1
        }
        throw PackageOperationException("无法创建本次 APK 提取工作目录")
    }

    private fun checkedDestination(workspace: File, fileName: String): File {
        require('/' !in fileName && '\\' !in fileName) { "APK file name must not contain separators" }
        val destination = File(workspace, fileName).canonicalFile
        val workspacePath = workspace.canonicalFile.path + File.separator
        if (!destination.path.startsWith(workspacePath)) {
            throw PackageOperationException("检测到工作目录路径越界：$fileName")
        }
        return destination
    }

    private fun ensureInsideWorkspaceRoot(directory: File) {
        try {
            val rootPath = workspaceRoot.canonicalFile.path + File.separator
            val directoryPath = directory.canonicalFile.path + File.separator
            if (!directoryPath.startsWith(rootPath)) {
                throw PackageOperationException("工作目录不在应用私有目录中")
            }
        } catch (exception: IOException) {
            throw PackageOperationException("无法校验工作目录：${exception.message}", exception)
        }
    }

    private fun androidUserId(): Int = Process.myUid() / ANDROID_UIDS_PER_USER

    private fun CommandResult.requireSuccessfulOutput(
        action: String,
        allowBlankOutput: Boolean = false,
    ): String {
        if (!succeeded) {
            val reason = when {
                timedOut -> "命令执行超时"
                failure != null -> failure
                combinedOutput().isNotBlank() -> combinedOutput()
                else -> "退出码 ${exitCode ?: "未知"}"
            }
            throw PackageOperationException("$action 失败：$reason")
        }
        if (!allowBlankOutput && stdout.isBlank()) {
            throw PackageOperationException("$action 失败：命令输出为空")
        }
        return stdout
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val WORKSPACES_DIRECTORY = "workspaces"
        const val ANDROID_UIDS_PER_USER = 100_000
        const val MAX_WORKSPACE_ATTEMPTS = 10
        const val HASH_BUFFER_BYTES = 64 * 1024
    }
}
