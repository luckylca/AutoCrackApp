package com.luckylca.autocrack.runtime

import com.luckylca.autocrack.root.CommandResult
import com.luckylca.autocrack.root.RootCommandRunner
import com.luckylca.autocrack.root.RootDetector
import com.luckylca.autocrack.root.RootToolCommand
import com.luckylca.autocrack.root.RootToolExecutor
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class DynamicHostReadBridge(
    private val layout: RuntimeLayout,
    private val rootDetector: RootDetector,
    private val runner: RootCommandRunner,
) {
    val auditFile: File = File(layout.auditRoot, "dynamic-host-read.jsonl")

    suspend fun listProcesses(
        filter: String = "",
        maxCount: Int = DEFAULT_PROCESS_LIMIT,
    ): HostProcessListReport {
        val executor = requireExecutor()
        val command = RootToolCommand.ListHostProcesses(filter = filter, maxCount = maxCount)
        val result = executeAudited(executor, command, pid = null, filter = filter)
        return HostProcessListReport(
            filter = filter,
            capturedAtEpochMillis = System.currentTimeMillis(),
            commandResult = result,
            processes = if (result.succeeded) {
                DynamicHostOutputParser.parseProcesses(result.stdout)
            } else {
                emptyList()
            },
        )
    }

    suspend fun inspectProcess(pid: Int): HostProcessInspectionReport {
        require(pid > 0) { "PID 必须是正整数" }
        val executor = requireExecutor()
        val identity = executeAudited(executor, RootToolCommand.ReadProcessIdentity(pid), pid)
        val preflight = executeAudited(
            executor,
            RootToolCommand.ReadProcessAttachPreflight(pid),
            pid,
        )
        val maps = executeAudited(executor, RootToolCommand.ReadProcessMaps(pid), pid)
        val threads = executeAudited(executor, RootToolCommand.ListProcessThreads(pid), pid)
        val fileDescriptors = executeAudited(
            executor,
            RootToolCommand.ListProcessFileDescriptors(pid),
            pid,
        )
        return HostProcessInspectionReport(
            pid = pid,
            capturedAtEpochMillis = System.currentTimeMillis(),
            identity = identity,
            attachPreflight = preflight,
            maps = maps,
            threads = threads,
            fileDescriptors = fileDescriptors,
            loadedModules = if (maps.succeeded) {
                DynamicHostOutputParser.parseLoadedModules(maps.stdout)
            } else {
                emptyList()
            },
        )
    }

    private suspend fun requireExecutor(): RootToolExecutor {
        layout.initialize()
        val rootStatus = rootDetector.inspect()
        require(rootStatus.isRootGranted) {
            rootStatus.diagnostic ?: "动态宿主检查需要 Root 权限"
        }
        val suPath = requireNotNull(rootStatus.suPath) { "Root 已授权但没有可用的 su 路径" }
        return RootToolExecutor(runner, suPath)
    }

    private suspend fun executeAudited(
        executor: RootToolExecutor,
        command: RootToolCommand,
        pid: Int?,
        filter: String? = null,
    ): CommandResult {
        val result = executor.execute(command)
        appendAudit(command, pid, filter, result)
        return result
    }

    private suspend fun appendAudit(
        command: RootToolCommand,
        pid: Int?,
        filter: String?,
        result: CommandResult,
    ) = withContext(Dispatchers.IO) {
        auditFile.parentFile?.mkdirs()
        val record = JSONObject()
            .put("schemaVersion", 1)
            .put("timestampEpochMillis", System.currentTimeMillis())
            .put("operation", command::class.java.simpleName)
            .put("label", command.label)
            .put("pid", pid ?: JSONObject.NULL)
            .put("filter", filter?.takeIf(String::isNotBlank) ?: JSONObject.NULL)
            .put("readOnly", true)
            .put("stateChanged", false)
            .put("attachAttempted", false)
            .put("exitCode", result.exitCode ?: JSONObject.NULL)
            .put("timedOut", result.timedOut)
            .put("failure", result.failure ?: JSONObject.NULL)
            .put("stdoutChars", result.stdout.length)
            .put("stderrChars", result.stderr.length)
        synchronized(AUDIT_LOCK) {
            auditFile.appendText(record.toString() + "\n", Charsets.UTF_8)
        }
    }

    private companion object {
        const val DEFAULT_PROCESS_LIMIT = 512
        val AUDIT_LOCK = Any()
    }
}
