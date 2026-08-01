package com.luckylca.autocrack.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.luckylca.autocrack.BuildConfig
import com.luckylca.autocrack.agent.LlmAgentAnswer
import com.luckylca.autocrack.agent.LlmPromptBuilder
import com.luckylca.autocrack.agent.LlmProviderConfig
import com.luckylca.autocrack.agent.LocalEvidenceSearchEngine
import com.luckylca.autocrack.agent.OpenAiCompatibleClient
import com.luckylca.autocrack.agent.PhaseFiveTestReportFormatter
import com.luckylca.autocrack.agent.PhaseFiveTestSnapshot
import com.luckylca.autocrack.agent.SecureLlmConfigStore
import com.luckylca.autocrack.analysis.ApkStaticAnalyzer
import com.luckylca.autocrack.analysis.StaticAnalysisReport
import com.luckylca.autocrack.apk.ExtractionReport
import com.luckylca.autocrack.apk.InstalledApp
import com.luckylca.autocrack.apk.InstalledAppKind
import com.luckylca.autocrack.apk.PackageRepository
import com.luckylca.autocrack.dex.DexEvidenceKind
import com.luckylca.autocrack.dex.DexIndexBuilder
import com.luckylca.autocrack.dex.DexIndexSummary
import com.luckylca.autocrack.dex.LocalAgentResult
import com.luckylca.autocrack.root.ProcessRootCommandRunner
import com.luckylca.autocrack.root.RootDetector
import com.luckylca.autocrack.root.RootStatus
import kotlinx.coroutines.launch

private sealed interface PhaseFiveAppListState {
    data object Loading : PhaseFiveAppListState
    data class Ready(val apps: List<InstalledApp>) : PhaseFiveAppListState
    data class Error(val message: String) : PhaseFiveAppListState
}

private data class AgentWorkspace(
    val extraction: ExtractionReport,
    val staticReport: StaticAnalysisReport,
    val dexIndex: DexIndexSummary,
)

private sealed interface AgentWorkspaceState {
    data object Idle : AgentWorkspaceState
    data class Running(val packageName: String, val stage: String) : AgentWorkspaceState
    data class Ready(val workspace: AgentWorkspace) : AgentWorkspaceState
    data class Error(val packageName: String, val message: String) : AgentWorkspaceState
}

private sealed interface AgentQueryState {
    data object Idle : AgentQueryState
    data class Running(val stage: String) : AgentQueryState
    data class Success(
        val localResult: LocalAgentResult,
        val llmAnswer: LlmAgentAnswer?,
    ) : AgentQueryState

    data class Error(val message: String) : AgentQueryState
}

@Composable
fun PhaseFiveScreen() {
    val context = LocalContext.current.applicationContext
    val runner = remember { ProcessRootCommandRunner() }
    val detector = remember(runner) { RootDetector(runner) }
    val repository = remember(context, runner) { PackageRepository(context, runner) }
    val staticAnalyzer = remember(context) { ApkStaticAnalyzer(context) }
    val dexIndexBuilder = remember { DexIndexBuilder() }
    val searchEngine = remember { LocalEvidenceSearchEngine() }
    val llmClient = remember { OpenAiCompatibleClient() }
    val configStore = remember(context) { SecureLlmConfigStore(context) }
    val scope = rememberCoroutineScope()

    var refreshKey by remember { mutableIntStateOf(0) }
    var rootStatus by remember { mutableStateOf<RootStatus?>(null) }
    var appListState by remember {
        mutableStateOf<PhaseFiveAppListState>(PhaseFiveAppListState.Loading)
    }
    var workspaceState by remember { mutableStateOf<AgentWorkspaceState>(AgentWorkspaceState.Idle) }
    var queryState by remember { mutableStateOf<AgentQueryState>(AgentQueryState.Idle) }
    var searchQuery by remember { mutableStateOf("") }
    var agentQuestion by remember { mutableStateOf("") }

    var savedConfig by remember { mutableStateOf<LlmProviderConfig?>(null) }
    var baseUrlInput by remember { mutableStateOf("") }
    var modelInput by remember { mutableStateOf("") }
    var apiKeyInput by remember { mutableStateOf("") }
    var configMessage by remember { mutableStateOf<String?>(null) }

    var stabilityStatus by remember { mutableStateOf("待确认") }
    var accuracyStatus by remember { mutableStateOf("待确认") }
    var testNote by remember { mutableStateOf("") }

    LaunchedEffect(refreshKey) {
        rootStatus = null
        appListState = PhaseFiveAppListState.Loading
        workspaceState = AgentWorkspaceState.Idle
        queryState = AgentQueryState.Idle
        stabilityStatus = "待确认"
        accuracyStatus = "待确认"

        savedConfig = configStore.load().also { config ->
            if (config != null) {
                baseUrlInput = config.baseUrl
                modelInput = config.model
            }
        }

        val status = detector.inspect()
        rootStatus = status
        appListState = if (!status.isRootGranted) {
            PhaseFiveAppListState.Error(status.diagnostic ?: "Root 未授权，无法读取应用列表")
        } else {
            try {
                PhaseFiveAppListState.Ready(repository.listInstalledApps(status))
            } catch (exception: Exception) {
                PhaseFiveAppListState.Error(exception.message ?: "读取应用列表时发生未知错误")
            }
        }
    }

    val allApps = (appListState as? PhaseFiveAppListState.Ready)?.apps.orEmpty()
    val filteredApps = remember(allApps, searchQuery) {
        val query = searchQuery.trim()
        if (query.isBlank()) {
            allApps
        } else {
            allApps.filter { app ->
                app.packageName.contains(query, ignoreCase = true) ||
                    app.primaryApkPath.orEmpty().contains(query, ignoreCase = true)
            }
        }
    }
    val workspaceRunning = workspaceState is AgentWorkspaceState.Running
    val queryRunning = queryState is AgentQueryState.Running

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "AutoCrackApp",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Phase 5 · DEX Evidence Agent MVP",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "选择应用后建立本地 DEX 类、方法、字段和字符串索引。你可以直接输入一句分析问题，先得到本地证据，再选择是否把受限证据上下文发送给外部 OpenAI-compatible 模型。APK、DEX 和 SO 文件本身不会上传。",
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        item { PhaseFiveDeviceCard() }

        item {
            val status = rootStatus
            if (status == null) {
                PhaseFiveLoadingCard("正在检测 Root / KernelSU")
            } else {
                PhaseFiveInfoCard("Root 状态") {
                    PhaseFiveInfoRow("访问状态", status.accessState.name)
                    PhaseFiveInfoRow("Root 管理器", status.provider.name)
                    PhaseFiveInfoRow("su 路径", status.suPath ?: "未找到")
                    PhaseFiveInfoRow(
                        "UID / GID",
                        status.identity?.let { "${it.uid ?: "?"} / ${it.gid ?: "?"}" } ?: "未知",
                    )
                    PhaseFiveInfoRow("SELinux", status.identity?.selinuxContext ?: "未知")
                    status.diagnostic?.let { diagnostic ->
                        Text(diagnostic, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        item {
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { refreshKey += 1 },
                enabled = rootStatus != null && !workspaceRunning && !queryRunning,
            ) {
                Text("重新检测并刷新应用列表")
            }
        }

        item {
            PhaseFiveModelConfigCard(
                savedConfig = savedConfig,
                baseUrl = baseUrlInput,
                onBaseUrlChange = { baseUrlInput = it },
                model = modelInput,
                onModelChange = { modelInput = it },
                apiKey = apiKeyInput,
                onApiKeyChange = { apiKeyInput = it },
                message = configMessage,
                onSave = {
                    runCatching {
                        val key = apiKeyInput.ifBlank {
                            savedConfig?.apiKey ?: error("首次配置必须输入 API Key")
                        }
                        val config = LlmProviderConfig(
                            baseUrl = baseUrlInput,
                            model = modelInput,
                            apiKey = key,
                        ).validated()
                        configStore.save(config)
                        savedConfig = config
                        baseUrlInput = config.baseUrl
                        modelInput = config.model
                        apiKeyInput = ""
                        configMessage = "配置已使用 Android Keystore 加密保存"
                    }.onFailure { exception ->
                        configMessage = exception.message ?: "保存模型配置失败"
                    }
                },
                onClear = {
                    configStore.clear()
                    savedConfig = null
                    baseUrlInput = ""
                    modelInput = ""
                    apiKeyInput = ""
                    configMessage = "已清除外部模型配置"
                },
            )
        }

        when (val state = workspaceState) {
            AgentWorkspaceState.Idle -> Unit
            is AgentWorkspaceState.Running -> item {
                PhaseFiveInfoCard("正在建立 Agent 工作区") {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    PhaseFiveInfoRow("目标包名", state.packageName)
                    PhaseFiveInfoRow("当前阶段", state.stage)
                    Text("大型应用首次建立 DEX 索引会明显慢于基础静态盘点，请保持应用在前台。")
                }
            }

            is AgentWorkspaceState.Error -> item {
                PhaseFiveInfoCard("工作区建立失败") {
                    PhaseFiveInfoRow("目标包名", state.packageName)
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                }
            }

            is AgentWorkspaceState.Ready -> {
                item { AgentWorkspaceSummaryCard(state.workspace) }
                item {
                    PhaseFiveAgentQuestionCard(
                        question = agentQuestion,
                        onQuestionChange = { agentQuestion = it },
                        queryRunning = queryRunning,
                        hasModelConfig = savedConfig != null,
                        onLocalAnalyze = {
                            val workspace = state.workspace
                            scope.launch {
                                queryState = AgentQueryState.Running("正在本地检索 DEX 证据")
                                queryState = try {
                                    val local = searchEngine.answer(
                                        packageName = workspace.extraction.packageName,
                                        question = agentQuestion,
                                        indexSummary = workspace.dexIndex,
                                    )
                                    AgentQueryState.Success(local, null)
                                } catch (exception: Exception) {
                                    AgentQueryState.Error(exception.message ?: "本地分析失败")
                                }
                            }
                        },
                        onModelAnalyze = {
                            val workspace = state.workspace
                            scope.launch {
                                queryState = AgentQueryState.Running("正在检索本地证据")
                                try {
                                    val local = searchEngine.answer(
                                        packageName = workspace.extraction.packageName,
                                        question = agentQuestion,
                                        indexSummary = workspace.dexIndex,
                                    )
                                    val config = configStore.load()
                                        ?: error("尚未保存外部模型配置")
                                    queryState = AgentQueryState.Running("正在向外部模型发送受限证据上下文")
                                    val prompt = LlmPromptBuilder.build(
                                        packageName = workspace.extraction.packageName,
                                        staticReport = workspace.staticReport,
                                        dexIndex = workspace.dexIndex,
                                        localResult = local,
                                    )
                                    val answer = llmClient.complete(
                                        config = config,
                                        prompt = prompt,
                                        evidenceCount = local.evidence.size,
                                    )
                                    queryState = AgentQueryState.Success(local, answer)
                                } catch (exception: Exception) {
                                    queryState = AgentQueryState.Error(
                                        exception.message ?: "外部模型分析失败",
                                    )
                                }
                            }
                        },
                    )
                }

                when (val query = queryState) {
                    AgentQueryState.Idle -> Unit
                    is AgentQueryState.Running -> item {
                        PhaseFiveInfoCard("Agent 正在处理") {
                            CircularProgressIndicator()
                            Text(query.stage, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    is AgentQueryState.Error -> item {
                        PhaseFiveInfoCard("Agent 分析失败") {
                            Text(query.message, color = MaterialTheme.colorScheme.error)
                        }
                    }

                    is AgentQueryState.Success -> {
                        item { LocalAgentResultCard(query.localResult) }
                        query.llmAnswer?.let { answer ->
                            item { LlmAnswerCard(answer) }
                        }
                    }
                }

                item {
                    PhaseFiveTestCard(
                        workspace = state.workspace,
                        queryState = queryState,
                        stabilityStatus = stabilityStatus,
                        onStabilityChange = { stabilityStatus = it },
                        accuracyStatus = accuracyStatus,
                        onAccuracyChange = { accuracyStatus = it },
                        note = testNote,
                        onNoteChange = { testNote = it },
                    )
                }
            }
        }

        when (val state = appListState) {
            PhaseFiveAppListState.Loading -> item {
                PhaseFiveLoadingCard("正在读取当前用户的已安装应用")
            }

            is PhaseFiveAppListState.Error -> item {
                PhaseFiveInfoCard("应用列表不可用") {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                }
            }

            is PhaseFiveAppListState.Ready -> {
                item {
                    PhaseFiveInfoCard("已安装应用") {
                        PhaseFiveInfoRow("总数", state.apps.size.toString())
                        PhaseFiveInfoRow("当前筛选结果", filteredApps.size.toString())
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            label = { Text("搜索包名或 APK 路径") },
                            singleLine = true,
                        )
                    }
                }

                items(
                    items = filteredApps,
                    key = InstalledApp::packageName,
                ) { app ->
                    PhaseFiveInstalledAppCard(
                        app = app,
                        busy = workspaceRunning || queryRunning,
                        onBuildWorkspace = {
                            val status = rootStatus ?: return@PhaseFiveInstalledAppCard
                            scope.launch {
                                workspaceState = AgentWorkspaceState.Running(
                                    app.packageName,
                                    "提取 Base / Split APK 并计算 SHA-256",
                                )
                                queryState = AgentQueryState.Idle
                                stabilityStatus = "待确认"
                                accuracyStatus = "待确认"
                                try {
                                    val extraction = repository.extractPackage(status, app.packageName)
                                    workspaceState = AgentWorkspaceState.Running(
                                        app.packageName,
                                        "解析 Manifest、签名、DEX、资源与 SO 清单",
                                    )
                                    val staticReport = staticAnalyzer.analyze(extraction)
                                    workspaceState = AgentWorkspaceState.Running(
                                        app.packageName,
                                        "建立类、方法、字段和字符串 DEX 索引",
                                    )
                                    val dexIndex = dexIndexBuilder.build(extraction)
                                    workspaceState = AgentWorkspaceState.Ready(
                                        AgentWorkspace(extraction, staticReport, dexIndex),
                                    )
                                    if (agentQuestion.isBlank()) {
                                        agentQuestion = "分析这个应用的登录、Token 保存和加密相关实现"
                                    }
                                } catch (exception: Exception) {
                                    workspaceState = AgentWorkspaceState.Error(
                                        app.packageName,
                                        exception.message ?: "建立 Agent 工作区失败",
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }

        item {
            Text(
                text = "Phase 5 只建立静态证据索引。它不会反编译为完整 Java 源码、不会执行目标代码，也不会自动确认运行时调用路径。下一阶段将在当前证据层上增加方法级字符串引用、调用关系与可审计工具规划。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun PhaseFiveModelConfigCard(
    savedConfig: LlmProviderConfig?,
    baseUrl: String,
    onBaseUrlChange: (String) -> Unit,
    model: String,
    onModelChange: (String) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    message: String?,
    onSave: () -> Unit,
    onClear: () -> Unit,
) {
    PhaseFiveInfoCard("外部模型配置（可选）") {
        PhaseFiveInfoRow("当前状态", if (savedConfig == null) "未配置，仅可本地分析" else "已加密保存")
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = baseUrl,
            onValueChange = onBaseUrlChange,
            label = { Text("OpenAI-compatible Base URL") },
            supportingText = { Text("必须使用 HTTPS；可填写 https://example.com/v1") },
            singleLine = true,
        )
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = model,
            onValueChange = onModelChange,
            label = { Text("模型名称") },
            singleLine = true,
        )
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = apiKey,
            onValueChange = onApiKeyChange,
            label = { Text(if (savedConfig == null) "API Key" else "API Key（留空则保留原值）") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSave) { Text("保存配置") }
            OutlinedButton(onClick = onClear, enabled = savedConfig != null) { Text("清除") }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        Text(
            "只有点击“外部模型分析”时才会联网；发送内容仅包含静态摘要和最多 60 条本地证据。Root 设备上的密钥无法承诺绝对安全。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PhaseFiveInstalledAppCard(
    app: InstalledApp,
    busy: Boolean,
    onBuildWorkspace: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(app.packageName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                if (app.kind == InstalledAppKind.SYSTEM) "系统应用 · UID ${app.uid ?: "未知"}" else
                    "用户应用 · UID ${app.uid ?: "未知"}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                app.primaryApkPath ?: "pm 未返回主 APK 路径",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onBuildWorkspace,
                enabled = !busy,
            ) {
                Text("建立 Agent 工作区")
            }
        }
    }
}

@Composable
private fun AgentWorkspaceSummaryCard(workspace: AgentWorkspace) {
    val context = LocalContext.current
    PhaseFiveInfoCard("Agent 工作区已就绪") {
        PhaseFiveInfoRow("包名", workspace.extraction.packageName)
        PhaseFiveInfoRow("版本", workspace.staticReport.manifest.versionName ?: "未知")
        PhaseFiveInfoRow("APK 数量", workspace.extraction.artifacts.size.toString())
        PhaseFiveInfoRow("APK 总大小", Formatter.formatShortFileSize(context, workspace.extraction.totalBytes))
        PhaseFiveInfoRow("DEX 条目", workspace.dexIndex.dexEntryCount.toString())
        PhaseFiveInfoRow("定义类", workspace.dexIndex.classCount.toString())
        PhaseFiveInfoRow("定义方法", workspace.dexIndex.methodCount.toString())
        PhaseFiveInfoRow("定义字段", workspace.dexIndex.fieldCount.toString())
        PhaseFiveInfoRow("索引字符串", workspace.dexIndex.stringCount.toString())
        PhaseFiveInfoRow("索引大小", Formatter.formatShortFileSize(context, workspace.dexIndex.indexBytes))
        PhaseFiveInfoRow("索引耗时", "${workspace.dexIndex.durationMillis} ms")
        PhaseFiveInfoRow("索引数据库", workspace.dexIndex.databasePath)
    }
}

@Composable
private fun PhaseFiveAgentQuestionCard(
    question: String,
    onQuestionChange: (String) -> Unit,
    queryRunning: Boolean,
    hasModelConfig: Boolean,
    onLocalAnalyze: () -> Unit,
    onModelAnalyze: () -> Unit,
) {
    PhaseFiveInfoCard("一句话分析") {
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = question,
            onValueChange = onQuestionChange,
            label = { Text("例如：分析登录请求、Token 保存方式和加密实现") },
            minLines = 3,
            maxLines = 6,
        )
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = onLocalAnalyze,
            enabled = !queryRunning && question.trim().length >= 4,
        ) {
            Text("仅使用本地证据分析")
        }
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = onModelAnalyze,
            enabled = !queryRunning && hasModelConfig && question.trim().length >= 4,
        ) {
            Text("本地检索后调用外部模型")
        }
        if (!hasModelConfig) {
            Text("尚未配置外部模型，第二个按钮不可用。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LocalAgentResultCard(result: LocalAgentResult) {
    PhaseFiveInfoCard("本地证据分析") {
        PhaseFiveInfoRow("搜索词", result.expandedTerms.joinToString())
        PhaseFiveInfoRow("证据数量", result.evidence.size.toString())
        PhaseFiveInfoRow("耗时", "${result.durationMillis} ms")
        Text(result.localSummary)
        result.evidence.take(30).forEachIndexed { index, evidence ->
            HorizontalDivider()
            Text(
                "${index + 1}. [${evidence.kind.name}] ${evidence.symbol}",
                fontWeight = FontWeight.SemiBold,
            )
            Text(evidence.dexEntry, style = MaterialTheme.typography.bodySmall)
            Text(evidence.detail, style = MaterialTheme.typography.bodySmall)
            Text(
                "matched=${evidence.matchedTerms.joinToString()} · score=${evidence.score}",
                style = MaterialTheme.typography.labelSmall,
                color = when (evidence.kind) {
                    DexEvidenceKind.METHOD -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        PhaseFiveInfoRow("完整结果", result.resultFilePath)
    }
}

@Composable
private fun LlmAnswerCard(answer: LlmAgentAnswer) {
    PhaseFiveInfoCard("外部模型回答") {
        PhaseFiveInfoRow("模型", answer.model)
        PhaseFiveInfoRow("服务主机", answer.endpointHost)
        PhaseFiveInfoRow("发送证据", answer.requestEvidenceCount.toString())
        PhaseFiveInfoRow("耗时", "${answer.durationMillis} ms")
        Text(answer.content)
    }
}

@Composable
private fun PhaseFiveTestCard(
    workspace: AgentWorkspace,
    queryState: AgentQueryState,
    stabilityStatus: String,
    onStabilityChange: (String) -> Unit,
    accuracyStatus: String,
    onAccuracyChange: (String) -> Unit,
    note: String,
    onNoteChange: (String) -> Unit,
) {
    val context = LocalContext.current
    val success = queryState as? AgentQueryState.Success
    PhaseFiveInfoCard("Phase 5 真机测试结果") {
        Text("测试工作区、DEX 索引和一句话分析后，确认下面两项，再点击复制。")
        PhaseFiveInfoRow("目标包名", workspace.extraction.packageName)
        PhaseFiveInfoRow("索引类 / 方法 / 字符串", "${workspace.dexIndex.classCount} / ${workspace.dexIndex.methodCount} / ${workspace.dexIndex.stringCount}")
        PhaseFiveInfoRow("本地证据", success?.localResult?.evidence?.size?.toString() ?: "尚未测试")
        PhaseFiveInfoRow("外部模型", success?.llmAnswer?.model ?: "未测试或未配置")
        StatusSelector("稳定性", stabilityStatus, onStabilityChange)
        StatusSelector("结果准确性", accuracyStatus, onAccuracyChange)
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = note,
            onValueChange = onNoteChange,
            label = { Text("补充说明") },
            minLines = 2,
            maxLines = 5,
        )
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val snapshot = PhaseFiveTestSnapshot(
                    versionName = BuildConfig.VERSION_NAME,
                    device = "${Build.MANUFACTURER} ${Build.MODEL}",
                    androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                    abi = Build.SUPPORTED_ABIS.joinToString(),
                    extraction = workspace.extraction,
                    staticReport = workspace.staticReport,
                    dexIndex = workspace.dexIndex,
                    localResult = success?.localResult,
                    llmAnswer = success?.llmAnswer,
                    stabilityStatus = stabilityStatus,
                    accuracyStatus = accuracyStatus,
                    note = note,
                )
                val text = PhaseFiveTestReportFormatter.format(snapshot)
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("AutoCrackApp Phase 5 测试结果", text))
                Toast.makeText(context, "测试结果已复制", Toast.LENGTH_SHORT).show()
            },
        ) {
            Text("复制完整测试结果")
        }
    }
}

@Composable
private fun StatusSelector(
    label: String,
    value: String,
    onChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$label：$value", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("正常", "有问题", "待确认").forEach { option ->
                TextButton(onClick = { onChange(option) }) {
                    Text(if (value == option) "● $option" else option)
                }
            }
        }
    }
}

@Composable
private fun PhaseFiveDeviceCard() {
    PhaseFiveInfoCard("设备环境") {
        PhaseFiveInfoRow("版本", BuildConfig.VERSION_NAME)
        PhaseFiveInfoRow("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        PhaseFiveInfoRow("设备", "${Build.MANUFACTURER} ${Build.MODEL}")
        PhaseFiveInfoRow("ABI", Build.SUPPORTED_ABIS.joinToString())
    }
}

@Composable
private fun PhaseFiveLoadingCard(message: String) {
    PhaseFiveInfoCard("运行状态") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator()
            Column {
                Text(message, fontWeight = FontWeight.SemiBold)
                Text("首次操作时 KernelSU 可能弹出授权窗口。")
            }
        }
    }
}

@Composable
private fun PhaseFiveInfoCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun PhaseFiveInfoRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
