package com.luckylca.autocrack.agent

import android.content.Context
import com.luckylca.autocrack.root.ProcessRootCommandRunner
import com.luckylca.autocrack.root.RootDetector
import com.luckylca.autocrack.runtime.AgentExecutionForegroundService
import com.luckylca.autocrack.runtime.RuntimeLayout
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
    private val clients = ConcurrentHashMap<String, MobileAgentToolClient>()
    private val runtimes = ConcurrentHashMap<String, MobileAgentRuntimeSession>()
    private val foregroundLeases = ConcurrentHashMap<String, String>()
    private val approvalWaiters = ConcurrentHashMap<String, CompletableDeferred<DangerousOperationDecision>>()
    private val mutableApprovals = MutableStateFlow<Map<String, DangerousOperationRequest>>(emptyMap())
    private val mutableTasks = MutableStateFlow(loadAndRecoverTasks())

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
        if (jobs[conversationId]?.isActive == true) return false
        conversationStore.appendUser(conversationId, message, attachments)
        val now = System.currentTimeMillis()
        updateTask(
            MobileAgentTaskSnapshot(
                conversationId = conversationId,
                status = MobileAgentTaskStatus.RUNNING,
                stage = "准备 Agent",
                streamingText = "",
                startedAtEpochMillis = now,
                updatedAtEpochMillis = now,
            ),
            persist = true,
        )
        val job = scope.launch { execute(conversationId, config) }
        jobs[conversationId] = job
        appendAudit("start", conversationId, JSONObject().put("attachmentCount", attachments.size))
        return true
    }

    fun stop(conversationId: String): Boolean {
        val job = jobs[conversationId] ?: return false
        clients[conversationId]?.cancelCurrent()
        runtimes[conversationId]?.cancelAllCommands?.invoke()
        job.cancel(CancellationException("用户停止 Agent"))
        return true
    }

    fun snapshot(conversationId: String): MobileAgentTaskSnapshot? = mutableTasks.value[conversationId]

    private suspend fun execute(conversationId: String, config: LlmProviderConfig) {
        var runtime: MobileAgentRuntimeSession? = null
        var leaseId: String? = null
        val client = MobileAgentToolClient()
        val preferences = preferencesStore.load()
        clients[conversationId] = client
        try {
            val initialConversation = requireNotNull(conversationStore.get(conversationId)) { "会话不存在：$conversationId" }
            leaseId = AgentExecutionForegroundService.acquire(
                appContext,
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
                        stage == "thinking" -> "Agent 正在思考"
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
            runtimes.remove(conversationId)?.cancelAllCommands?.invoke()
            runtime?.tools?.closeSafely()
            clients.remove(conversationId)?.cancelCurrent()
            foregroundLeases.remove(conversationId)
            leaseId?.let { AgentExecutionForegroundService.release(appContext, it) }
            jobs.remove(conversationId)
        }
    }

    private suspend fun requestDangerousOperationApproval(
        request: DangerousOperationRequest,
    ): DangerousOperationDecision {
        val preferences = preferencesStore.load()
        if (request.category.name in preferences.alwaysAllowedDangerousCategories) {
            return DangerousOperationDecision.ALLOW_ONCE
        }
        if (request.category == DangerousOperationCategory.SYSTEM_WRITE) {
            when (preferences.systemWritePolicy) {
                SystemWritePolicy.DENY -> return DangerousOperationDecision.DENY
                SystemWritePolicy.ALLOW -> return DangerousOperationDecision.ALLOW_ONCE
                SystemWritePolicy.ASK -> Unit
            }
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
        val startIndex = conversation.summaryThroughMessageId?.let { boundary ->
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
        appendAudit("compaction", conversationId, JSONObject().put("messageCount", toCompact.size))
        val summary = client.summarizeForCompaction(
            config = config,
            existingSummary = conversation.summary,
            messages = toCompact,
        )
        return conversationStore.updateSummary(
            conversationId = conversationId,
            summary = summary,
            throughMessageId = toCompact.last().id,
        )
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
        val loaded = readTasks()
        var changed = false
        val recovered = loaded.mapValues { (_, task) ->
            if (task.status == MobileAgentTaskStatus.RUNNING) {
                changed = true
                task.copy(
                    status = MobileAgentTaskStatus.INTERRUPTED,
                    stage = "上次任务因进程重启而中断",
                    streamingText = "",
                    updatedAtEpochMillis = System.currentTimeMillis(),
                    error = "任务执行进程已重启，可以在原会话中继续。",
                )
            } else {
                task
            }
        }
        if (changed) writeTasks(recovered)
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
                            error = item.optString("error").takeIf(String::isNotBlank),
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
        private const val COMPACT_MESSAGE_THRESHOLD = 36
        private const val COMPACT_CHAR_THRESHOLD = 52_000
        private const val RECENT_PROTOCOL_MESSAGES = 16

        @Volatile
        private var instance: MobileAgentTaskCoordinator? = null

        fun get(context: Context): MobileAgentTaskCoordinator = instance ?: synchronized(this) {
            instance ?: MobileAgentTaskCoordinator(context).also { instance = it }
        }
    }
}
