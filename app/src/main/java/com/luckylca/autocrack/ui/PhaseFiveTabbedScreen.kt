package com.luckylca.autocrack.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import com.luckylca.autocrack.agent.PhaseFiveDiagnosticEvent
import com.luckylca.autocrack.agent.PhaseFiveDiagnosticReportFormatter
import com.luckylca.autocrack.agent.PhaseFiveDiagnosticSeverity
import com.luckylca.autocrack.agent.PhaseFiveDiagnosticSnapshot
import com.luckylca.autocrack.agent.SecureLlmConfigStore
import com.luckylca.autocrack.agent.phaseFiveDiagnosticEvent
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

private enum class PhaseFiveTab(val title: String) {
    APPS("应用"),
    WORKSPACE("工作区"),
    ANALYSIS("分析"),
    MODEL("模型"),
    DIAGNOSTICS("诊断"),
}

private sealed interface TabbedAppListState {
    data object Loading : TabbedAppListState
    data class Ready(val apps: List<InstalledApp>) : TabbedAppListState
    data class Error(val message: String) : TabbedAppListState
}

private data class TabbedAgentWorkspace(
    val extraction: ExtractionReport,
    val staticReport: StaticAnalysisReport,
    val dexIndex: DexIndexSummary,
)

private sealed interface TabbedWorkspaceState {
    data object Idle : TabbedWorkspaceState
    data class Running(val packageName: String, val stage: String) : TabbedWorkspaceState
    data class Ready(val workspace: TabbedAgentWorkspace) : TabbedWorkspaceState
    data class Error(val packageName: String, val stage: String, val message: String) : TabbedWorkspaceState
}

private sealed interface TabbedQueryState {
    data object Idle : TabbedQueryState
    data class Running(val stage: String) : TabbedQueryState
    data class Success(val mode: String) : TabbedQueryState
    data class Error(val stage: String, val message: String) : TabbedQueryState
}

@Composable
fun PhaseFiveTabbedScreen() {
    val uiContext = LocalContext.current
    val appContext = uiContext.applicationContext
    val runner = remember { ProcessRootCommandRunner() }
    val detector = remember(runner) { RootDetector(runner) }
    val repository = remember(appContext, runner) { PackageRepository(appContext, runner) }
    val staticAnalyzer = remember(appContext) { ApkStaticAnalyzer(appContext) }
    val dexIndexBuilder = remember { DexIndexBuilder() }
    val searchEngine = remember { LocalEvidenceSearchEngine() }
    val llmClient = remember { OpenAiCompatibleClient() }
    val configStore = remember(appContext) { SecureLlmConfigStore(appContext) }
    val scope = rememberCoroutineScope()

    var selectedTab by remember { mutableStateOf(PhaseFiveTab.APPS) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var rootStatus by remember { mutableStateOf<RootStatus?>(null) }
    var appListState by remember { mutableStateOf<TabbedAppListState>(TabbedAppListState.Loading) }
    var workspaceState by remember { mutableStateOf<TabbedWorkspaceState>(TabbedWorkspaceState.Idle) }
    var queryState by remember { mutableStateOf<TabbedQueryState>(TabbedQueryState.Idle) }
    var selectedPackageName by remember { mutableStateOf<String?>(null) }
    var appSearch by remember { mutableStateOf("") }
    var agentQuestion by remember { mutableStateOf("") }
    var lastLocalResult by remember { mutableStateOf<LocalAgentResult?>(null) }
    var lastLlmAnswer by remember { mutableStateOf<LlmAgentAnswer?>(null) }

    var savedConfig by remember { mutableStateOf<LlmProviderConfig?>(null) }
    var baseUrlInput by remember { mutableStateOf("") }
    var modelInput by remember { mutableStateOf("") }
    var apiKeyInput by remember { mutableStateOf("") }
    var configMessage by remember { mutableStateOf<String?>(null) }

    var diagnosticEvents by remember { mutableStateOf<List<PhaseFiveDiagnosticEvent>>(emptyList()) }
    var diagnosticNote by remember { mutableStateOf("") }

    fun recordEvent(
        stage: String,
        message: String,
        severity: PhaseFiveDiagnosticSeverity = PhaseFiveDiagnosticSeverity.INFO,
        throwable: Throwable? = null,
    ) {
        diagnosticEvents = (
            diagnosticEvents + phaseFiveDiagnosticEvent(stage, message, severity, throwable)
            ).takeLast(MAX_DIAGNOSTIC_EVENTS)
    }

    LaunchedEffect(refreshKey) {
        rootStatus = null
        appListState = TabbedAppListState.Loading
        workspaceState = TabbedWorkspaceState.Idle
        queryState = TabbedQueryState.Idle
        selectedPackageName = null
        lastLocalResult = null
        lastLlmAnswer = null
        recordEvent("初始化", "开始检测 Root 并读取应用列表")

        runCatching { configStore.load() }
            .onSuccess { config ->
                savedConfig = config
                if (config != null) {
                    baseUrlInput = config.baseUrl
                    modelInput = config.model
                }
            }
            .onFailure { exception ->
                recordEvent(
                    stage = "模型配置",
                    message = exception.message ?: "读取加密模型配置失败",
                    severity = PhaseFiveDiagnosticSeverity.ERROR,
                    throwable = exception,
                )
            }

        val status = runCatching { detector.inspect() }
            .onFailure { exception ->
                recordEvent(
                    stage = "Root 检测",
                    message = exception.message ?: "Root 检测异常",
                    severity = PhaseFiveDiagnosticSeverity.ERROR,
                    throwable = exception,
                )
            }
            .getOrNull()
        rootStatus = status

        if (status == null) {
            appListState = TabbedAppListState.Error("Root 检测失败")
            return@LaunchedEffect
        }
        if (!status.isRootGranted) {
            val message = status.diagnostic ?: "Root 未授权，无法读取应用列表"
            appListState = TabbedAppListState.Error(message)
            recordEvent("Root 检测", message, PhaseFiveDiagnosticSeverity.ERROR)
            return@LaunchedEffect
        }

        try {
            val apps = repository.listInstalledApps(status)
            appListState = TabbedAppListState.Ready(apps)
            recordEvent("应用列表", "成功读取 ${apps.size} 个应用")
        } catch (exception: Exception) {
            val message = exception.message ?: "读取应用列表时发生未知错误"
            appListState = TabbedAppListState.Error(message)
            recordEvent("应用列表", message, PhaseFiveDiagnosticSeverity.ERROR, exception)
        }
    }

    val allApps = (appListState as? TabbedAppListState.Ready)?.apps.orEmpty()
    val filteredApps = remember(allApps, appSearch) {
        val query = appSearch.trim()
        if (query.isBlank()) {
            allApps
        } else {
            allApps.filter { app ->
                app.packageName.contains(query, ignoreCase = true) ||
                    app.primaryApkPath.orEmpty().contains(query, ignoreCase = true)
            }
        }
    }
    val workspaceRunning = workspaceState is TabbedWorkspaceState.Running
    val queryRunning = queryState is TabbedQueryState.Running
    val readyWorkspace = (workspaceState as? TabbedWorkspaceState.Ready)?.workspace

    fun buildWorkspace(app: InstalledApp) {
        val status = rootStatus ?: return
        selectedPackageName = app.packageName
        selectedTab = PhaseFiveTab.WORKSPACE
        lastLocalResult = null
        lastLlmAnswer = null
        queryState = TabbedQueryState.Idle

        scope.launch {
            var stage = "APK 提取"
            workspaceState = TabbedWorkspaceState.Running(app.packageName, stage)
            recordEvent(stage, "开始提取 ${app.packageName} 的 Base / Split APK")
            try {
                val extraction = repository.extractPackage(status, app.packageName)
                recordEvent(stage, "提取完成，APK 数量 ${extraction.artifacts.size}")

                stage = "静态分析"
                workspaceState = TabbedWorkspaceState.Running(app.packageName, stage)
                recordEvent(stage, "开始解析 Manifest、签名、DEX、资源与 SO")
                val staticReport = staticAnalyzer.analyze(extraction)
                recordEvent(
                    stage,
                    "静态分析完成，DEX ${staticReport.dexFileCount}，SO ${staticReport.nativeLibraryCount}",
                )

                stage = "DEX 索引"
                workspaceState = TabbedWorkspaceState.Running(app.packageName, stage)
                recordEvent(stage, "开始建立类、方法、字段和字符串索引")
                val dexIndex = dexIndexBuilder.build(extraction)
                val workspace = TabbedAgentWorkspace(extraction, staticReport, dexIndex)
                workspaceState = TabbedWorkspaceState.Ready(workspace)
                recordEvent(
                    stage,
                    "索引完成：类 ${dexIndex.classCount}，方法 ${dexIndex.methodCount}，" +
                        "字符串 ${dexIndex.stringCount}，耗时 ${dexIndex.durationMillis} ms",
                )
                if (agentQuestion.isBlank()) {
                    agentQuestion = "分析这个应用的登录、Token 保存和加密相关实现"
                }
                selectedTab = PhaseFiveTab.ANALYSIS
            } catch (exception: Exception) {
                val message = exception.message ?: "建立 Agent 工作区失败"
                workspaceState = TabbedWorkspaceState.Error(app.packageName, stage, message)
                recordEvent(stage, message, PhaseFiveDiagnosticSeverity.ERROR, exception)
            }
        }
    }

    fun runLocalAnalysis(workspace: TabbedAgentWorkspace) {
        scope.launch {
            val stage = "本地证据检索"
            queryState = TabbedQueryState.Running(stage)
            lastLlmAnswer = null
            recordEvent(stage, "开始分析问题：${agentQuestion.trim()}")
            try {
                val local = searchEngine.answer(
                    packageName = workspace.extraction.packageName,
                    question = agentQuestion,
                    indexSummary = workspace.dexIndex,
                )
                lastLocalResult = local
                queryState = TabbedQueryState.Success("本地分析")
                recordEvent(
                    stage,
                    "检索完成，返回 ${local.evidence.size} 条证据，耗时 ${local.durationMillis} ms",
                )
            } catch (exception: Exception) {
                val message = exception.message ?: "本地分析失败"
                queryState = TabbedQueryState.Error(stage, message)
                recordEvent(stage, message, PhaseFiveDiagnosticSeverity.ERROR, exception)
            }
        }
    }

    fun runModelAnalysis(workspace: TabbedAgentWorkspace) {
        scope.launch {
            var stage = "本地证据检索"
            queryState = TabbedQueryState.Running(stage)
            lastLlmAnswer = null
            recordEvent(stage, "开始为外部模型准备本地证据")
            try {
                val local = searchEngine.answer(
                    packageName = workspace.extraction.packageName,
                    question = agentQuestion,
                    indexSummary = workspace.dexIndex,
                )
                lastLocalResult = local
                recordEvent(stage, "本地检索完成，返回 ${local.evidence.size} 条证据")

                stage = "外部模型请求"
                queryState = TabbedQueryState.Running(stage)
                val config = configStore.load() ?: error("尚未保存外部模型配置")
                val prompt = LlmPromptBuilder.build(
                    packageName = workspace.extraction.packageName,
                    staticReport = workspace.staticReport,
                    dexIndex = workspace.dexIndex,
                    localResult = local,
                )
                recordEvent(stage, "开始向 ${config.baseUrl} 的 ${config.model} 发送受限证据上下文")
                val answer = llmClient.complete(
                    config = config,
                    prompt = prompt,
                    evidenceCount = local.evidence.size,
                )
                lastLlmAnswer = answer
                queryState = TabbedQueryState.Success("外部模型分析")
                recordEvent(stage, "模型回答完成，耗时 ${answer.durationMillis} ms")
            } catch (exception: Exception) {
                val message = exception.message ?: "外部模型分析失败"
                queryState = TabbedQueryState.Error(stage, message)
                recordEvent(stage, message, PhaseFiveDiagnosticSeverity.ERROR, exception)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(
                text = "AutoCrackApp",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = selectedPackageName ?: "Phase 5.2 · Bottom Navigation DEX Agent",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when (selectedTab) {
                PhaseFiveTab.APPS -> AppsTab(
                    rootStatus = rootStatus,
                    appListState = appListState,
                    filteredApps = filteredApps,
                    searchQuery = appSearch,
                    onSearchChange = { appSearch = it },
                    busy = workspaceRunning || queryRunning,
                    onRefresh = { refreshKey += 1 },
                    onBuildWorkspace = ::buildWorkspace,
                )

                PhaseFiveTab.WORKSPACE -> WorkspaceTab(
                    state = workspaceState,
                    onOpenApps = { selectedTab = PhaseFiveTab.APPS },
                    onOpenAnalysis = { selectedTab = PhaseFiveTab.ANALYSIS },
                    onOpenDiagnostics = { selectedTab = PhaseFiveTab.DIAGNOSTICS },
                )

                PhaseFiveTab.ANALYSIS -> AnalysisTab(
                    workspace = readyWorkspace,
                    question = agentQuestion,
                    onQuestionChange = { agentQuestion = it },
                    queryState = queryState,
                    localResult = lastLocalResult,
                    llmAnswer = lastLlmAnswer,
                    hasModelConfig = savedConfig != null,
                    onLocalAnalyze = { readyWorkspace?.let(::runLocalAnalysis) },
                    onModelAnalyze = { readyWorkspace?.let(::runModelAnalysis) },
                    onOpenApps = { selectedTab = PhaseFiveTab.APPS },
                    onOpenModel = { selectedTab = PhaseFiveTab.MODEL },
                    onOpenDiagnostics = { selectedTab = PhaseFiveTab.DIAGNOSTICS },
                )

                PhaseFiveTab.MODEL -> ModelTab(
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
                            recordEvent("模型配置", "成功保存 ${config.model} / ${config.baseUrl}")
                        }.onFailure { exception ->
                            val message = exception.message ?: "保存模型配置失败"
                            configMessage = message
                            recordEvent(
                                "模型配置",
                                message,
                                PhaseFiveDiagnosticSeverity.ERROR,
                                exception,
                            )
                        }
                    },
                    onClear = {
                        runCatching { configStore.clear() }
                            .onSuccess {
                                savedConfig = null
                                baseUrlInput = ""
                                modelInput = ""
                                apiKeyInput = ""
                                configMessage = "已清除外部模型配置"
                                recordEvent("模型配置", "已清除外部模型配置")
                            }
                            .onFailure { exception ->
                                val message = exception.message ?: "清除模型配置失败"
                                configMessage = message
                                recordEvent(
                                    "模型配置",
                                    message,
                                    PhaseFiveDiagnosticSeverity.ERROR,
                                    exception,
                                )
                            }
                    },
                )

                PhaseFiveTab.DIAGNOSTICS -> DiagnosticsTab(
                    selectedPackageName = selectedPackageName,
                    rootStatus = rootStatus,
                    appListState = appListState,
                    workspaceState = workspaceState,
                    queryState = queryState,
                    workspace = readyWorkspace,
                    localResult = lastLocalResult,
                    llmAnswer = lastLlmAnswer,
                    events = diagnosticEvents,
                    note = diagnosticNote,
                    onNoteChange = { diagnosticNote = it },
                    onCopy = {
                        val snapshot = PhaseFiveDiagnosticSnapshot(
                            versionName = BuildConfig.VERSION_NAME,
                            device = "${Build.MANUFACTURER} ${Build.MODEL}",
                            androidVersion =
                                "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                            abi = Build.SUPPORTED_ABIS.joinToString(),
                            selectedPackageName = selectedPackageName,
                            rootStatus = rootStatus.toDiagnosticText(),
                            appListStatus = appListState.toDiagnosticText(),
                            workspaceStatus = workspaceState.toDiagnosticText(),
                            queryStatus = queryState.toDiagnosticText(),
                            extraction = readyWorkspace?.extraction,
                            staticReport = readyWorkspace?.staticReport,
                            dexIndex = readyWorkspace?.dexIndex,
                            localResult = lastLocalResult,
                            llmAnswer = lastLlmAnswer,
                            events = diagnosticEvents,
                            note = diagnosticNote,
                        )
                        val report = PhaseFiveDiagnosticReportFormatter.format(snapshot)
                        val clipboard = uiContext.getSystemService(ClipboardManager::class.java)
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText("AutoCrackApp Phase 5 诊断报告", report),
                        )
                        Toast.makeText(
                            uiContext,
                            "完整诊断报告已复制",
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                    onClear = {
                        diagnosticEvents = listOf(
                            phaseFiveDiagnosticEvent("诊断", "用户清空了旧诊断事件"),
                        )
                    },
                )
            }
        }

        NavigationBar(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            PhaseFiveTab.entries.forEach { tab ->
                val selected = selectedTab == tab
                NavigationBarItem(
                    selected = selected,
                    onClick = { selectedTab = tab },
                    icon = {
                        Text(
                            text = tab.title.take(1),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    label = { Text(tab.title) },
                    alwaysShowLabel = true,
                )
            }
        }
    }
}

@Composable
private fun AppsTab(
    rootStatus: RootStatus?,
    appListState: TabbedAppListState,
    filteredApps: List<InstalledApp>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    busy: Boolean,
    onRefresh: () -> Unit,
    onBuildWorkspace: (InstalledApp) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(Modifier.height(4.dp)) }
        item {
            TabbedInfoCard("设备与 Root") {
                TabbedInfoRow("版本", BuildConfig.VERSION_NAME)
                TabbedInfoRow("设备", "${Build.MANUFACTURER} ${Build.MODEL}")
                TabbedInfoRow("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                if (rootStatus == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator()
                        Text("正在检测 Root / KernelSU")
                    }
                } else {
                    TabbedInfoRow("访问状态", rootStatus.accessState.name)
                    TabbedInfoRow("Root 管理器", rootStatus.provider.name)
                    TabbedInfoRow("su 路径", rootStatus.suPath ?: "未找到")
                    rootStatus.diagnostic?.let { diagnostic ->
                        Text(diagnostic, color = MaterialTheme.colorScheme.error)
                    }
                }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onRefresh,
                    enabled = !busy,
                ) {
                    Text("重新检测并刷新")
                }
            }
        }

        when (appListState) {
            TabbedAppListState.Loading -> item { TabbedProgressCard("正在读取已安装应用") }
            is TabbedAppListState.Error -> item {
                TabbedInfoCard("应用列表失败") {
                    Text(appListState.message, color = MaterialTheme.colorScheme.error)
                }
            }
            is TabbedAppListState.Ready -> {
                item {
                    TabbedInfoCard("选择应用") {
                        TabbedInfoRow("应用总数", appListState.apps.size.toString())
                        TabbedInfoRow("筛选结果", filteredApps.size.toString())
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = searchQuery,
                            onValueChange = onSearchChange,
                            label = { Text("搜索包名或 APK 路径") },
                            singleLine = true,
                        )
                    }
                }
                items(filteredApps, key = InstalledApp::packageName) { app ->
                    TabbedAppCard(app, busy) { onBuildWorkspace(app) }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun WorkspaceTab(
    state: TabbedWorkspaceState,
    onOpenApps: () -> Unit,
    onOpenAnalysis: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(Modifier.height(4.dp)) }
        when (state) {
            TabbedWorkspaceState.Idle -> item {
                TabbedInfoCard("尚未建立工作区") {
                    Text("先在“应用”中选择目标应用。")
                    Button(modifier = Modifier.fillMaxWidth(), onClick = onOpenApps) {
                        Text("选择应用")
                    }
                }
            }
            is TabbedWorkspaceState.Running -> item {
                TabbedInfoCard("正在建立工作区") {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    TabbedInfoRow("目标包名", state.packageName)
                    TabbedInfoRow("当前阶段", state.stage)
                    Text("大型应用索引可能需要数分钟。切换底部页面不会中断任务。")
                }
            }
            is TabbedWorkspaceState.Error -> item {
                TabbedInfoCard("工作区建立失败") {
                    TabbedInfoRow("目标包名", state.packageName)
                    TabbedInfoRow("失败阶段", state.stage)
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onOpenDiagnostics,
                    ) {
                        Text("查看并复制完整诊断")
                    }
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onOpenApps,
                    ) {
                        Text("返回应用列表")
                    }
                }
            }
            is TabbedWorkspaceState.Ready -> {
                item { WorkspaceSummaryCard(state.workspace) }
                item {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onOpenAnalysis,
                    ) {
                        Text("进入一句话分析")
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun AnalysisTab(
    workspace: TabbedAgentWorkspace?,
    question: String,
    onQuestionChange: (String) -> Unit,
    queryState: TabbedQueryState,
    localResult: LocalAgentResult?,
    llmAnswer: LlmAgentAnswer?,
    hasModelConfig: Boolean,
    onLocalAnalyze: () -> Unit,
    onModelAnalyze: () -> Unit,
    onOpenApps: () -> Unit,
    onOpenModel: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(Modifier.height(4.dp)) }
        if (workspace == null) {
            item {
                TabbedInfoCard("没有可分析的工作区") {
                    Text("先选择应用并等待 DEX 索引完成。")
                    Button(modifier = Modifier.fillMaxWidth(), onClick = onOpenApps) {
                        Text("选择应用")
                    }
                }
            }
        } else {
            item {
                TabbedInfoCard("一句话分析") {
                    TabbedInfoRow("目标包名", workspace.extraction.packageName)
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = question,
                        onValueChange = onQuestionChange,
                        label = { Text("输入要分析的问题") },
                        minLines = 3,
                        maxLines = 7,
                    )
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onLocalAnalyze,
                        enabled = queryState !is TabbedQueryState.Running &&
                            question.trim().length >= MIN_QUESTION_LENGTH,
                    ) {
                        Text("仅使用本地证据分析")
                    }
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onModelAnalyze,
                        enabled = queryState !is TabbedQueryState.Running &&
                            hasModelConfig && question.trim().length >= MIN_QUESTION_LENGTH,
                    ) {
                        Text("本地检索后调用外部模型")
                    }
                    if (!hasModelConfig) {
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = onOpenModel,
                        ) {
                            Text("配置外部模型")
                        }
                    }
                }
            }

            when (queryState) {
                TabbedQueryState.Idle -> Unit
                is TabbedQueryState.Running -> item { TabbedProgressCard(queryState.stage) }
                is TabbedQueryState.Success -> item {
                    TabbedInfoCard("分析完成") {
                        TabbedInfoRow("模式", queryState.mode)
                    }
                }
                is TabbedQueryState.Error -> item {
                    TabbedInfoCard("Agent 分析失败") {
                        TabbedInfoRow("失败阶段", queryState.stage)
                        Text(queryState.message, color = MaterialTheme.colorScheme.error)
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = onOpenDiagnostics,
                        ) {
                            Text("查看异常与堆栈")
                        }
                    }
                }
            }

            localResult?.let { result -> item { LocalEvidenceCard(result) } }
            llmAnswer?.let { answer -> item { ModelAnswerCard(answer) } }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun ModelTab(
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
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(Modifier.height(4.dp)) }
        item {
            TabbedInfoCard("外部模型配置") {
                TabbedInfoRow(
                    "状态",
                    if (savedConfig == null) "未配置" else "已使用 Android Keystore 加密保存",
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = baseUrl,
                    onValueChange = onBaseUrlChange,
                    label = { Text("OpenAI-compatible Base URL") },
                    supportingText = { Text("仅允许 HTTPS，例如 https://example.com/v1") },
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
                    label = {
                        Text(if (savedConfig == null) "API Key" else "API Key（留空保留原值）")
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                Button(modifier = Modifier.fillMaxWidth(), onClick = onSave) {
                    Text("保存配置")
                }
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onClear,
                    enabled = savedConfig != null,
                ) {
                    Text("清除配置")
                }
                message?.let { status ->
                    Text(status, color = MaterialTheme.colorScheme.primary)
                }
                Text(
                    "只有主动点击外部模型分析时才联网；APK、DEX、SO 文件不会上传。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun DiagnosticsTab(
    selectedPackageName: String?,
    rootStatus: RootStatus?,
    appListState: TabbedAppListState,
    workspaceState: TabbedWorkspaceState,
    queryState: TabbedQueryState,
    workspace: TabbedAgentWorkspace?,
    localResult: LocalAgentResult?,
    llmAnswer: LlmAgentAnswer?,
    events: List<PhaseFiveDiagnosticEvent>,
    note: String,
    onNoteChange: (String) -> Unit,
    onCopy: () -> Unit,
    onClear: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(Modifier.height(4.dp)) }
        item {
            TabbedInfoCard("当前状态快照") {
                TabbedInfoRow("目标包名", selectedPackageName ?: "未选择")
                TabbedInfoRow("Root", rootStatus.toDiagnosticText())
                TabbedInfoRow("应用列表", appListState.toDiagnosticText())
                TabbedInfoRow("工作区", workspaceState.toDiagnosticText())
                TabbedInfoRow("Agent", queryState.toDiagnosticText())
                workspace?.let { ready ->
                    TabbedInfoRow(
                        "索引大小",
                        Formatter.formatShortFileSize(LocalContext.current, ready.dexIndex.indexBytes),
                    )
                    TabbedInfoRow("索引耗时", "${ready.dexIndex.durationMillis} ms")
                }
                TabbedInfoRow("本地证据", localResult?.evidence?.size?.toString() ?: "无成功结果")
                TabbedInfoRow("外部模型", llmAnswer?.model ?: "无成功结果")
            }
        }
        item {
            TabbedInfoCard("完整诊断报告") {
                Text(
                    "报告会自动包含失败阶段、错误文本、异常类型、堆栈、工作区统计和最近运行事件，" +
                        "不再依赖手工填写“有问题”。",
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = note,
                    onValueChange = onNoteChange,
                    label = { Text("额外补充说明（可选）") },
                    minLines = 2,
                    maxLines = 5,
                )
                Button(modifier = Modifier.fillMaxWidth(), onClick = onCopy) {
                    Text("复制完整诊断报告")
                }
                OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onClear) {
                    Text("清空旧诊断事件")
                }
            }
        }
        item {
            TabbedInfoCard("自动诊断事件（${events.size}）") {
                if (events.isEmpty()) {
                    Text("暂无事件")
                } else {
                    events.takeLast(MAX_VISIBLE_EVENTS).asReversed().forEach { event ->
                        HorizontalDivider()
                        Text(
                            text = "[${event.severity}] ${event.stage}",
                            fontWeight = FontWeight.SemiBold,
                            color = if (event.severity == PhaseFiveDiagnosticSeverity.ERROR) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        Text(event.message)
                        event.exceptionType?.let { type ->
                            Text(type, style = MaterialTheme.typography.bodySmall)
                        }
                        event.stackTrace
                            ?.lineSequence()
                            ?.take(MAX_VISIBLE_STACK_LINES)
                            ?.joinToString("\n")
                            ?.let { preview ->
                                Text(
                                    preview,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun TabbedAppCard(
    app: InstalledApp,
    busy: Boolean,
    onBuild: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                app.packageName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (app.kind == InstalledAppKind.SYSTEM) {
                    "系统应用 · UID ${app.uid ?: "未知"}"
                } else {
                    "用户应用 · UID ${app.uid ?: "未知"}"
                },
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
                onClick = onBuild,
                enabled = !busy,
            ) {
                Text("建立工作区")
            }
        }
    }
}

@Composable
private fun WorkspaceSummaryCard(workspace: TabbedAgentWorkspace) {
    val context = LocalContext.current
    TabbedInfoCard("工作区已就绪") {
        TabbedInfoRow("包名", workspace.extraction.packageName)
        TabbedInfoRow("版本", workspace.staticReport.manifest.versionName ?: "未知")
        TabbedInfoRow("APK 数量", workspace.extraction.artifacts.size.toString())
        TabbedInfoRow(
            "APK 总大小",
            Formatter.formatShortFileSize(context, workspace.extraction.totalBytes),
        )
        TabbedInfoRow("静态 DEX", workspace.staticReport.dexFileCount.toString())
        TabbedInfoRow("索引 DEX 条目", workspace.dexIndex.dexEntryCount.toString())
        TabbedInfoRow(
            "类 / 方法 / 字段",
            "${workspace.dexIndex.classCount} / ${workspace.dexIndex.methodCount} / " +
                workspace.dexIndex.fieldCount,
        )
        TabbedInfoRow("字符串", workspace.dexIndex.stringCount.toString())
        TabbedInfoRow("跳过字符串", workspace.dexIndex.skippedStringCount.toString())
        TabbedInfoRow(
            "索引大小",
            Formatter.formatShortFileSize(context, workspace.dexIndex.indexBytes),
        )
        TabbedInfoRow("索引耗时", "${workspace.dexIndex.durationMillis} ms")
        TabbedInfoRow("数据库", workspace.dexIndex.databasePath)
    }
}

@Composable
private fun LocalEvidenceCard(result: LocalAgentResult) {
    TabbedInfoCard("本地证据") {
        TabbedInfoRow("搜索词", result.expandedTerms.joinToString())
        TabbedInfoRow("证据数量", result.evidence.size.toString())
        TabbedInfoRow("耗时", "${result.durationMillis} ms")
        Text(result.localSummary)
        result.evidence.take(MAX_VISIBLE_EVIDENCE).forEachIndexed { index, evidence ->
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
                color = if (evidence.kind == DexEvidenceKind.METHOD) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        TabbedInfoRow("完整结果", result.resultFilePath)
    }
}

@Composable
private fun ModelAnswerCard(answer: LlmAgentAnswer) {
    TabbedInfoCard("外部模型回答") {
        TabbedInfoRow("模型", answer.model)
        TabbedInfoRow("服务主机", answer.endpointHost)
        TabbedInfoRow("发送证据", answer.requestEvidenceCount.toString())
        TabbedInfoRow("耗时", "${answer.durationMillis} ms")
        Text(answer.content)
    }
}

@Composable
private fun TabbedProgressCard(message: String) {
    TabbedInfoCard("运行中") {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(message, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun TabbedInfoCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun TabbedInfoRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun RootStatus?.toDiagnosticText(): String = when (this) {
    null -> "未完成检测"
    else -> buildString {
        append(accessState.name).append(" / ").append(provider.name)
        suPath?.let { path -> append(" / su=").append(path) }
        diagnostic?.let { text -> append(" / ").append(text) }
    }
}

private fun TabbedAppListState.toDiagnosticText(): String = when (this) {
    TabbedAppListState.Loading -> "读取中"
    is TabbedAppListState.Ready -> "完成，共 ${apps.size} 个应用"
    is TabbedAppListState.Error -> "失败：$message"
}

private fun TabbedWorkspaceState.toDiagnosticText(): String = when (this) {
    TabbedWorkspaceState.Idle -> "未开始"
    is TabbedWorkspaceState.Running -> "运行中：$stage / $packageName"
    is TabbedWorkspaceState.Ready -> "完成：${workspace.extraction.packageName}"
    is TabbedWorkspaceState.Error -> "失败：$stage / $message"
}

private fun TabbedQueryState.toDiagnosticText(): String = when (this) {
    TabbedQueryState.Idle -> "未开始"
    is TabbedQueryState.Running -> "运行中：$stage"
    is TabbedQueryState.Success -> "完成：$mode"
    is TabbedQueryState.Error -> "失败：$stage / $message"
}

private const val MAX_DIAGNOSTIC_EVENTS = 100
private const val MAX_VISIBLE_EVENTS = 30
private const val MAX_VISIBLE_STACK_LINES = 6
private const val MAX_VISIBLE_EVIDENCE = 30
private const val MIN_QUESTION_LENGTH = 4
