package com.luckylca.autocrack.agent

import android.content.Context
import com.luckylca.autocrack.root.ProcessRootCommandRunner
import com.luckylca.autocrack.root.RootDetector
import com.luckylca.autocrack.runtime.AgentExecutionForegroundService
import com.luckylca.autocrack.runtime.ChrootRuntimeEngine
import com.luckylca.autocrack.runtime.RootShellRuntimeEngine
import com.luckylca.autocrack.runtime.RuntimeLayout
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class MobileAgentTaskStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    INTERRUPTED,
}

data class MobileAgentTaskSnapshot(
    val conversationId: String,
    val status: MobileAgentTaskStatus,
    val stage: String,
    val streamingText: String,
    val startedAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val error: String? = null,
)

class MobileAgentTaskCoordinator private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val layout = RuntimeLayout(appContext).initialize()
    private val conversationStore = MobileAgentConversationStore(appContext)
    private val preferencesStore = MobileAgentPreferencesStore(appContext)
    private val runner = ProcessRootCommandRunner()
    private val rootDetector = RootDetector(runner)
    private val toolFactory = AgentToolSessionFactory(appContext, runner, rootDetector)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val taskFile = File(layout.sessionsRoot, "mobile-agent-tasks.json")
    private val agentAuditFile = File(layout.auditRoot, "mobile-agent-events.jsonl")
    private val taskLock = Any()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val starting = ConcurrentHashMap.newKeySet<String>()
    private val clients = ConcurrentHashMap<String, MobileAgentToolClient>()
    private val runtimes = ConcurrentHashMap<String, MobileAgentRuntimeSession>()
    private val foregroundLeases = ConcurrentHashMap<String, String>()
    private val approvalWaiters = ConcurrentHashMap<String, CompletableDeferred<DangerousOperationDecision>>()
    private val mutableApprovals = MutableStateFlow<Map<String, DangerousOperationRequest>>(emptyMap())
    private val mutableTasks = MutableStateFlow(loadAndRecoverTasks())
    // Start stale-process cleanup as soon as the coordinator is created. A previous Agent may have
    // been force-stopped or failed after spawning background work (for example a large JADX job).
    // Do not wait for the next user request before reclaiming those CPU/RAM resources.
    private val startupProcessCleanup = scope.async { cleanupRecoveredAgentProcesses() }

    val tasks: StateFlow<Map<String, MobileAgentTaskSnapshot>> = mutableTasks.asStateFlow()
    val approvals: StateFlow<Map<String, DangerousOperationRequest>> = mutableApprovals.asStateFlow()

    fun resolveApproval(requestId: String, decision: DangerousOperationDecision): Boolean {
        val request = mutableApprovals.value[requestId] ?: return false
        if (decision == DangerousOperationDecision.ALWAYS_ALLOW_CATEGORY) {
            preferencesStore.allowCategoryAlways(request.category.name)
        }
        mutableApprovals.value = mutableApprovals.value - requestId
        return approvalWaiters.remove(requestId)?.complete(decision) == true
    }

    suspend fun start(
        conversationId: String,
        userMessage: String,
        attachments: List<MobileAgentAttachment>,
        config: LlmProviderConfig,
    ): Boolean {
        val message = userMessage.trim()
        require(message.isNotBlank() || attachments.isNotEmpty()) { "消息和附件不能同时为空" }
        if (!starting.add(conversationId)) return false
        if (jobs[conversationId]?.isActive == true) {
            starting.remove(conversationId)
            return false
        }
        return try {
            conversationStore.appendUser(conversationId, message, attachments)
            beginExecution(conversationId, config, "start", attachments.size)
            true
        } finally {
            starting.remove(conversationId)
        }
    }

    suspend fun resume(conversationId: String, config: LlmProviderConfig): Boolean {
        if (!starting.add(conversationId)) return false
        if (jobs[conversationId]?.isActive == true) {
            starting.remove(conversationId)
            return false
        }
        return try {
            requireNotNull(conversationStore.get(conversationId)) { "会话不存在：$conversationId" }
            beginExecution(conversationId, config, "resume", attachmentCount = 0)
            true
        } finally {
            starting.remove(conversationId)
        }
    }

    fun stop(conversationId: String): Boolean {
        val job = jobs[conversationId] ?: return false
        clients[conversationId]?.cancelCurrent()
        runtimes[conversationId]?.cancelAllCommands?.invoke()
        job.cancel(CancellationException("用户停止 Agent"))
        return true
    }

    fun snapshot(conversationId: String): MobileAgentTaskSnapshot? = mutableTasks.value[conversationId]

    private fun beginExecution(
        conversationId: String,
        config: LlmProviderConfig,
        auditEvent: String,
        attachmentCount: Int,
    ) {
        val now = System.currentTimeMillis()
        updateTask(
            MobileAgentTaskSnapshot(
                conversationId = conversationId,
                status = MobileAgentTaskStatus.RUNNING,
                stage = if (auditEvent == "resume") "正在恢复 Agent" else "准备 Agent",
                streamingText = "",
                startedAtEpochMillis = now,
                updatedAtEpochMillis = now,
            ),
            persist = true,
        )
        val job = scope.launch(start = CoroutineStart.LAZY) { execute(conversationId, config) }
        jobs[conversationId] = job
        appendAudit(auditEvent, conversationId, JSONObject().put("attachmentCount", attachmentCount))
        job.start()
    }

    private suspend fun execute(conversationId: String, config: LlmProviderConfig) {
        var runtime: MobileAgentRuntimeSession? = null
        var leaseId: String? = null
        val client = MobileAgentToolClient()
        val preferences = preferencesStore.load()
        clients[conversationId] = client
        try {
            startupProcessCleanup.await()
            val initialConversation = requireNotNull(conversationStore.get(conversationId)) { "会话不存在：$conversationId" }
            val protocolRepair = MobileAgentProtocolRepair.repair(initialConversation.messages)
            if (protocolRepair.synthesizedToolResults > 0 || protocolRepair.droppedOrphanToolResults > 0) {
                appendAudit(
                    "protocol_repaired",
                    conversationId,
                    JSONObject()
                        .put("synthesizedToolResults", protocolRepair.synthesizedToolResults)
                        .put("droppedOrphanToolResults", protocolRepair.droppedOrphanToolResults),
                )
            }
            leaseId = AgentExecutionForegroundService.acquire(
                appContext,
                conversationId,
                initialConversation.title.ifBlank { "Mobile Agent" },
                "检查运行环境",
            )
            foregroundLeases[conversationId] = leaseId
            updateRunning(conversationId, "检查运行环境")
            runtime = toolFactory.createMobileAgent(
                sessionId = conversationId,
                knownRootStatus = null,
                dangerousOperationGate = ::requestDangerousOperationApproval,
                onStage = { stage ->
                    if (stage.startsWith("mobile_ready")) updateRunning(conversationId, "环境已就绪")
                },
            )
            runtimes[conversationId] = runtime
            var conversation = initialConversation
            if (preferences.contextCompressionEnabled) {
                conversation = compactIfNeeded(conversationId, config, client, conversation)
            }
            client.completeWithTools(
                config = config,
                systemPrompt = MobileAgentPromptBuilder.build(runtime, preferences),
                conversation = conversation,
                tools = runtime.tools.tools,
                dispatcher = runtime.tools::dispatch,
                maxToolRounds = preferences.maxToolIterations,
                contextCompressionEnabled = preferences.contextCompressionEnabled,
                onTextSnapshot = { text ->
                    mutableTasks.value[conversationId]?.let { current ->
                        if (current.stage != "Agent 正在回复") {
                            foregroundLeases[conversationId]?.let { AgentExecutionForegroundService.update(it, "Agent 正在回复") }
                        }
                        updateTask(
                            current.copy(
                                stage = "Agent 正在回复",
                                streamingText = text,
                                updatedAtEpochMillis = System.currentTimeMillis(),
                            ),
                            persist = false,
                        )
                    }
                },
                onStage = { stage ->
                    val label = when {
                        stage.startsWith("thinking:") -> "Agent 正在思考 · 第 ${stage.substringAfter(':')} 轮"
                        stage == "thinking" -> "Agent 正在思考"
                        stage.startsWith("compacting:") -> "正在压缩工作上下文 · 第 ${stage.substringAfter(':')} 轮"
                        stage.startsWith("tool:") -> "正在运行 ${stage.removePrefix("tool:")}"
                        else -> stage
                    }
                    updateRunning(conversationId, label)
                },
                onProtocolMessage = { protocolMessage ->
                    conversationStore.appendGenerated(conversationId, protocolMessage)
                },
            )
            markTerminal(conversationId, MobileAgentTaskStatus.COMPLETED, "已完成", null)
            appendAudit("completed", conversationId, JSONObject())
        } catch (cancelled: CancellationException) {
            markTerminal(conversationId, MobileAgentTaskStatus.CANCELLED, "已停止", cancelled.message)
            appendAudit("cancelled", conversationId, JSONObject().put("reason", cancelled.message ?: JSONObject.NULL))
        } catch (error: Exception) {
            markTerminal(
                conversationId,
                MobileAgentTaskStatus.FAILED,
                "执行失败",
                error.message ?: error::class.java.simpleName,
            )
            appendAudit("failed", conversationId, JSONObject().put("error", error.message ?: error::class.java.simpleName))
        } finally {
            val finishedRuntime = runtimes.remove(conversationId) ?: runtime
            finishedRuntime?.cancelAllCommands?.invoke()
            withContext(NonCancellable) {
                runCatching { finishedRuntime?.cleanupSessionProcesses?.invoke() }
                    .onFailure { cleanupError ->
                        appendAudit(
                            "session_process_cleanup_failed",
                            conversationId,
                            JSONObject().put("error", cleanupError.message ?: cleanupError::class.java.simpleName),
                        )
                    }
            }
            finishedRuntime?.tools?.closeSafely()
            clients.remove(conversationId)?.cancelCurrent()
            foregroundLeases.remove(conversationId)
            leaseId?.let { AgentExecutionForegroundService.release(appContext, it) }
            jobs.remove(conversationId)
        }
    }

    private suspend fun cleanupRecoveredAgentProcesses() {
        runCatching {
            val root = rootDetector.inspect()
            if (!root.isRootGranted) return@runCatching
            val suPath = root.suPath ?: return@runCatching
            val host = RootShellRuntimeEngine(layout = layout, suPath = suPath)
            val result = ChrootRuntimeEngine(layout, host).cleanupStaleAgentProcesses()
            check(result.succeeded) {
                result.failure ?: result.stderr.ifBlank { "遗留 Agent 子进程清理失败：exit=${result.exitCode}" }
            }
        }.onFailure { error ->
            appendAudit(
                "startup_process_cleanup_failed",
                "runtime",
                JSONObject().put("error", error.message ?: error::class.java.simpleName),
            )
        }
    }

    private suspend fun requestDangerousOperationApproval(
        request: DangerousOperationRequest,
    ): DangerousOperationDecision {
        val preferences = preferencesStore.load()
        if (request.category == DangerousOperationCategory.SYSTEM_WRITE) {
            when (preferences.systemWritePolicy) {
                SystemWritePolicy.DENY -> return DangerousOperationDecision.DENY
                SystemWritePolicy.ALLOW -> return DangerousOperationDecision.ALLOW_ONCE
                SystemWritePolicy.ASK -> Unit
            }
        }
        if (request.category.name in preferences.alwaysAllowedDangerousCategories) {
            return DangerousOperationDecision.ALLOW_ONCE
        }
        if (!preferences.dangerousOperationConfirmation) return DangerousOperationDecision.ALLOW_ONCE

        val waiter = CompletableDeferred<DangerousOperationDecision>()
        approvalWaiters[request.id] = waiter
        mutableApprovals.value = mutableApprovals.value + (request.id to request)
        appendAudit(
            "approval_requested",
            request.conversationId,
            JSONObject().put("category", request.category.name).put("command", request.command.take(2_000)),
        )
        return try {
            waiter.await()
        } finally {
            approvalWaiters.remove(request.id)
            mutableApprovals.value = mutableApprovals.value - request.id
        }
    }

    private suspend fun compactIfNeeded(
        conversationId: String,
        config: LlmProviderConfig,
        client: MobileAgentToolClient,
        conversation: MobileAgentConversation,
    ): MobileAgentConversation {
        val persistedSummaryIsUsable = MobileAgentCompactionPolicy.isUsablePersistedSummary(conversation.summary)
        val startIndex = conversation.summaryThroughMessageId
            ?.takeIf { persistedSummaryIsUsable }
            ?.let { boundary ->
            conversation.messages.indexOfFirst { it.id == boundary }.takeIf { it >= 0 }?.plus(1)
        } ?: 0
        val unsummarized = conversation.messages.drop(startIndex)
        val chars = unsummarized.sumOf { message ->
            message.content.length + (message.toolCallsJson?.length ?: 0) +
                message.attachments.sumOf { it.displayName.length + it.relativePath.length }
        }
        if (unsummarized.size <= COMPACT_MESSAGE_THRESHOLD && chars <= COMPACT_CHAR_THRESHOLD) return conversation

        val targetRecentStart = (unsummarized.size - RECENT_PROTOCOL_MESSAGES).coerceAtLeast(1)
        val recentUserStart = (targetRecentStart until unsummarized.size)
            .firstOrNull { unsummarized[it].role == MobileAgentRole.USER }
            ?: unsummarized.indices.lastOrNull { it > 0 && unsummarized[it].role == MobileAgentRole.USER }
            ?: return conversation
        val toCompact = unsummarized.take(recentUserStart)
        if (toCompact.isEmpty()) return conversation

        updateRunning(conversationId, "正在压缩较早的会话上下文")
        appendAudit(
            "compaction_started",
            conversationId,
            JSONObject()
                .put("messageCount", toCompact.size)
                .put("rebuildingInvalidSummary", conversation.summary != null && !persistedSummaryIsUsable),
        )
        val summary = try {
            client.summarizeForCompaction(
                config = config,
                existingSummary = conversation.summary.takeIf { persistedSummaryIsUsable },
                messages = toCompact,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            appendAudit(
                "compaction_rejected",
                conversationId,
                JSONObject()
                    .put("messageCount", toCompact.size)
                    .put("error", (error.message ?: error::class.java.simpleName).take(2_000)),
            )
            return conversation
        }
        val updated = conversationStore.updateSummary(
            conversationId = conversationId,
            summary = summary,
            throughMessageId = toCompact.last().id,
        )
        appendAudit(
            "compaction_completed",
            conversationId,
            JSONObject()
                .put("messageCount", toCompact.size)
                .put("summaryChars", summary.length)
                .put("throughMessageId", toCompact.last().id),
        )
        return updated
    }

    private fun appendAudit(event: String, conversationId: String, detail: JSONObject) {
        val record = JSONObject()
            .put("schemaVersion", 1)
            .put("event", event)
            .put("conversationId", conversationId)
            .put("timestampEpochMillis", System.currentTimeMillis())
            .put("detail", detail)
        synchronized(taskLock) {
            agentAuditFile.parentFile?.mkdirs()
            agentAuditFile.appendText(record.toString() + "\n", Charsets.UTF_8)
        }
    }

    private fun updateRunning(conversationId: String, stage: String) {
        val current = mutableTasks.value[conversationId] ?: return
        if (current.status != MobileAgentTaskStatus.RUNNING) return
        foregroundLeases[conversationId]?.let { AgentExecutionForegroundService.update(it, stage) }
        updateTask(
            current.copy(stage = stage, updatedAtEpochMillis = System.currentTimeMillis()),
            persist = true,
        )
    }

    private fun markTerminal(
        conversationId: String,
        status: MobileAgentTaskStatus,
        stage: String,
        error: String?,
    ) {
        val current = mutableTasks.value[conversationId]
        val now = System.currentTimeMillis()
        updateTask(
            MobileAgentTaskSnapshot(
                conversationId = conversationId,
                status = status,
                stage = stage,
                streamingText = "",
                startedAtEpochMillis = current?.startedAtEpochMillis ?: now,
                updatedAtEpochMillis = now,
                error = error,
            ),
            persist = true,
        )
    }

    private fun updateTask(snapshot: MobileAgentTaskSnapshot, persist: Boolean) {
        synchronized(taskLock) {
            mutableTasks.value = mutableTasks.value.toMutableMap().apply {
                put(snapshot.conversationId, snapshot)
            }.toMap()
            if (persist) writeTasks(mutableTasks.value)
        }
    }

    private fun loadAndRecoverTasks(): Map<String, MobileAgentTaskSnapshot> {
        val needsNullableNormalization = runCatching {
            taskFile.isFile && hasLegacyNullTaskErrors(JSONObject(taskFile.readText(Charsets.UTF_8)))
        }.getOrDefault(false)
        val loaded = readTasks()
        val recovered = recoverInterruptedTasks(loaded, System.currentTimeMillis())
        if (recovered != loaded || needsNullableNormalization) writeTasks(recovered)
        return recovered
    }

    private fun readTasks(): Map<String, MobileAgentTaskSnapshot> {
        if (!taskFile.isFile) return emptyMap()
        return runCatching {
            val array = JSONObject(taskFile.readText()).optJSONArray("tasks") ?: JSONArray()
            buildMap {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("conversationId")
                    val status = runCatching { MobileAgentTaskStatus.valueOf(item.optString("status")) }.getOrNull()
                    if (id.isBlank() || status == null) continue
                    put(
                        id,
                        MobileAgentTaskSnapshot(
                            conversationId = id,
                            status = status,
                            stage = item.optString("stage"),
                            streamingText = "",
                            startedAtEpochMillis = item.optLong("startedAtEpochMillis"),
                            updatedAtEpochMillis = item.optLong("updatedAtEpochMillis"),
                            error = item.optNonBlankStringOrNull("error"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun writeTasks(tasks: Map<String, MobileAgentTaskSnapshot>) {
        taskFile.parentFile?.mkdirs()
        val json = JSONObject()
            .put("schemaVersion", 1)
            .put(
                "tasks",
                JSONArray().apply {
                    tasks.values.sortedByDescending(MobileAgentTaskSnapshot::updatedAtEpochMillis).forEach { task ->
                        put(
                            JSONObject()
                                .put("conversationId", task.conversationId)
                                .put("status", task.status.name)
                                .put("stage", task.stage)
                                .put("startedAtEpochMillis", task.startedAtEpochMillis)
                                .put("updatedAtEpochMillis", task.updatedAtEpochMillis)
                                .put("error", task.error ?: JSONObject.NULL),
                        )
                    }
                },
            )
        val temp = File(taskFile.parentFile, "${taskFile.name}.tmp")
        temp.writeText(json.toString(2))
        if (!temp.renameTo(taskFile)) {
            taskFile.writeText(json.toString(2))
            temp.delete()
        }
    }

    companion object {
        private const val COMPACT_MESSAGE_THRESHOLD = 96
        private const val COMPACT_CHAR_THRESHOLD = 180_000
        private const val RECENT_PROTOCOL_MESSAGES = 16

        @Volatile
        private var instance: MobileAgentTaskCoordinator? = null

        fun get(context: Context): MobileAgentTaskCoordinator = instance ?: synchronized(this) {
            instance ?: MobileAgentTaskCoordinator(context).also { instance = it }
        }
    }
}

internal fun JSONObject.optNonBlankStringOrNull(name: String): String? =
    if (has(name) && !isNull(name)) {
        optString(name).takeIf { value -> value.isNotBlank() && value != "null" }
    } else {
        null
    }

internal fun recoverInterruptedTasks(
    tasks: Map<String, MobileAgentTaskSnapshot>,
    nowEpochMillis: Long,
): Map<String, MobileAgentTaskSnapshot> = tasks.mapValues { (_, task) ->
    if (task.status == MobileAgentTaskStatus.RUNNING) {
        task.copy(
            status = MobileAgentTaskStatus.INTERRUPTED,
            stage = "上次任务因进程重启而中断",
            streamingText = "",
            updatedAtEpochMillis = nowEpochMillis,
            error = "任务执行进程已重启，可以在原会话中继续。",
        )
    } else {
        task
    }
}

internal fun hasLegacyNullTaskErrors(root: JSONObject): Boolean {
    val tasks = root.optJSONArray("tasks") ?: return false
    return (0 until tasks.length()).any { index ->
        val item = tasks.optJSONObject(index)
        item != null && item.has("error") && !item.isNull("error") && item.optString("error") == "null"
    }
}
