package com.luckylca.autocrack.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.BackEventCompat
import androidx.core.content.ContextCompat

import android.net.Uri
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.luckylca.autocrack.agent.DangerousOperationDecision
import com.luckylca.autocrack.agent.DangerousOperationRequest
import com.luckylca.autocrack.agent.LlmApiProtocol
import com.luckylca.autocrack.agent.LlmProviderProbeClient
import com.luckylca.autocrack.agent.LlmProviderConfig
import com.luckylca.autocrack.agent.MobileAgentAttachment
import com.luckylca.autocrack.agent.MobileAgentAttachmentStore
import com.luckylca.autocrack.agent.MobileAgentConversation
import com.luckylca.autocrack.agent.MobileAgentConversationStore
import com.luckylca.autocrack.agent.MobileAgentPreferences
import com.luckylca.autocrack.agent.MobileAgentPreferencesStore
import com.luckylca.autocrack.agent.MobileAgentRole
import com.luckylca.autocrack.agent.MobileAgentTaskCoordinator
import com.luckylca.autocrack.agent.MobileAgentTaskSnapshot
import com.luckylca.autocrack.agent.MobileAgentTaskStatus
import com.luckylca.autocrack.agent.SecureLlmConfigStore
import com.luckylca.autocrack.agent.SystemWritePolicy
import com.luckylca.autocrack.root.ProcessRootCommandRunner
import com.luckylca.autocrack.root.RootDetector
import com.luckylca.autocrack.root.RootStatus
import com.luckylca.autocrack.runtime.ChrootPtySessionManager
import com.luckylca.autocrack.runtime.ChrootRuntimeEngine
import com.luckylca.autocrack.runtime.EnvironmentCheckItem
import com.luckylca.autocrack.runtime.InstalledToolpack
import com.luckylca.autocrack.runtime.MobileAgentEnvironmentProbe
import com.luckylca.autocrack.runtime.RootfsPackageInstaller
import com.luckylca.autocrack.runtime.RootShellRuntimeEngine
import com.luckylca.autocrack.runtime.RuntimeLayout
import com.luckylca.autocrack.runtime.RuntimeRootfsState
import com.luckylca.autocrack.runtime.ToolpackPackageInstaller
import com.luckylca.autocrack.runtime.unreferencedToolpackPackages
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private enum class MobileAgentMainTab(val label: String) {
    CONVERSATIONS("会话"),
    SETTINGS("设置"),
}

private enum class RootfsInstallMode { UPDATE, REBUILD }

private const val RUNNING_CONVERSATION_REFRESH_MILLIS = 1_500L
private const val CONVERSATION_SEARCH_DEBOUNCE_MILLIS = 250L

@Composable
internal fun MobilePiAgentScreen(
    routeRequest: MobileAgentRouteRequest? = null,
    onRouteConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val layout = remember(appContext) { RuntimeLayout(appContext).initialize() }
    val conversationStore = remember(appContext) { MobileAgentConversationStore(appContext) }
    val attachmentStore = remember(appContext) { MobileAgentAttachmentStore(appContext) }
    val configStore = remember(appContext) { SecureLlmConfigStore(appContext) }
    val providerProbe = remember { LlmProviderProbeClient() }
    val preferencesStore = remember(appContext) { MobileAgentPreferencesStore(appContext) }
    val taskCoordinator = remember(appContext) { MobileAgentTaskCoordinator.get(appContext) }
    val tasks by taskCoordinator.tasks.collectAsState()
    val approvals by taskCoordinator.approvals.collectAsState()
    val runner = remember { ProcessRootCommandRunner() }
    val rootDetector = remember(runner) { RootDetector(runner) }
    val toolpackInstaller = remember(appContext, layout) { ToolpackPackageInstaller(appContext, layout) }
    val rootfsInstaller = remember(appContext, layout) { RootfsPackageInstaller(appContext, layout) }
    val environmentProbe = remember(appContext) { MobileAgentEnvironmentProbe(appContext) }
    val ptyManager = remember(appContext) { ChrootPtySessionManager.get(appContext) }

    val navigationSaver = remember {
        Saver<MobileAgentNavigationHistory, String>(
            save = { history -> history.encode() },
            restore = MobileAgentNavigationHistory::decode,
        )
    }
    var navigation by rememberSaveable(stateSaver = navigationSaver) {
        mutableStateOf(MobileAgentNavigationHistory.initial(routeRequest?.route))
    }
    val currentDestination = navigation.current
    val predictiveBackProgress = remember { Animatable(0f) }
    var predictiveBackGestureActive by remember { mutableStateOf(false) }
    var predictiveBackEdge by remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }
    var conversations by remember { mutableStateOf<List<MobileAgentConversation>>(emptyList()) }
    var activeConversation by remember { mutableStateOf<MobileAgentConversation?>(null) }
    var input by remember { mutableStateOf("") }
    var pendingAttachments by remember { mutableStateOf<List<MobileAgentAttachment>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var searchMatchIds by remember { mutableStateOf<Set<String>?>(null) }
    var renameTarget by remember { mutableStateOf<MobileAgentConversation?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var uiStatus by remember { mutableStateOf<String?>(null) }
    var notificationPermissionPrompted by remember { mutableStateOf(false) }

    val initialProviderCatalog = remember(configStore) { configStore.loadCatalog() }
    var providerCatalog by remember { mutableStateOf(initialProviderCatalog) }
    val initialProvider = initialProviderCatalog.activeProvider
    val savedConfig = providerCatalog.activeProvider
    var editingProviderId by remember { mutableStateOf(initialProvider?.id ?: UUID.randomUUID().toString()) }
    var providerNameInput by remember { mutableStateOf(initialProvider?.name.orEmpty()) }
    var providerProtocol by remember { mutableStateOf(initialProvider?.protocol ?: LlmApiProtocol.OPENAI_CHAT) }
    var baseUrlInput by remember { mutableStateOf(initialProvider?.baseUrl.orEmpty()) }
    var modelInput by remember { mutableStateOf(initialProvider?.model.orEmpty()) }
    var apiKeyInput by remember { mutableStateOf("") }
    var availableModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var configBusy by remember { mutableStateOf(false) }
    var configStatus by remember { mutableStateOf<String?>(null) }

    var agentPreferences by remember { mutableStateOf(preferencesStore.load()) }
    var systemPromptInput by remember { mutableStateOf(agentPreferences.customSystemPrompt) }
    var maxToolIterationsInput by remember { mutableStateOf(agentPreferences.maxToolIterations.toString()) }

    var rootStatus by remember { mutableStateOf<RootStatus?>(null) }
    var environmentChecks by remember { mutableStateOf<List<EnvironmentCheckItem>>(emptyList()) }
    var environmentLoading by remember { mutableStateOf(false) }
    var environmentStatus by remember { mutableStateOf<String?>(null) }
    var rootfsInfo by remember { mutableStateOf(buildRootfsInfo(layout, emptyList())) }
    var rootfsStatus by remember { mutableStateOf<String?>(null) }
    var rootfsInstallMode by remember { mutableStateOf(RootfsInstallMode.UPDATE) }

    var installedToolpacks by remember { mutableStateOf<List<InstalledToolpack>>(emptyList()) }
    var selectedToolpack by remember { mutableStateOf<InstalledToolpack?>(null) }
    var toolpackStatus by remember { mutableStateOf<String?>(null) }
    var toolpackRefreshKey by remember { mutableIntStateOf(0) }

    var storageInfo by remember { mutableStateOf(StorageUiInfo()) }
    var storageRefreshKey by remember { mutableIntStateOf(0) }
    var agentLog by remember { mutableStateOf("") }
    var toolLog by remember { mutableStateOf("") }

    var pendingSave by remember { mutableStateOf<Pair<String, AgentManagedFile>?>(null) }

    suspend fun createToolpackSelfTestEngine(): ChrootRuntimeEngine {
        val root = rootDetector.inspect()
        require(root.isRootGranted) { root.diagnostic ?: "Toolpack 自检需要 Root" }
        val suPath = requireNotNull(root.suPath) { "Root 已授权但未找到可用 su" }
        return ChrootRuntimeEngine(
            layout = layout,
            hostEngine = RootShellRuntimeEngine(layout, suPath),
        )
    }

    fun loadProviderDraft(provider: LlmProviderConfig) {
        editingProviderId = provider.id
        providerNameInput = provider.name
        providerProtocol = provider.protocol
        baseUrlInput = provider.baseUrl
        modelInput = provider.model
        apiKeyInput = ""
        availableModels = emptyList()
    }

    fun newProviderDraft() {
        editingProviderId = UUID.randomUUID().toString()
        providerNameInput = ""
        providerProtocol = LlmApiProtocol.OPENAI_CHAT
        baseUrlInput = ""
        modelInput = ""
        apiKeyInput = ""
        availableModels = emptyList()
        configStatus = null
    }

    fun draftProviderConfig(): LlmProviderConfig {
        val existing = providerCatalog.providers.firstOrNull { it.id == editingProviderId }
        val key = apiKeyInput.ifBlank { existing?.apiKey ?: error("首次配置必须输入 API Key") }
        return LlmProviderConfig(
            id = editingProviderId,
            name = providerNameInput,
            protocol = providerProtocol,
            baseUrl = baseUrlInput,
            model = modelInput,
            apiKey = key,
        )
    }

    fun navigate(destination: MobileAgentDestination) {
        navigation = navigation.navigate(destination)
    }

    fun navigateSettings(page: AgentSettingsPage) {
        navigate(MobileAgentDestination.Settings(page))
    }

    fun navigateBack() {
        navigation = navigation.back()
    }

    PredictiveBackHandler(enabled = navigation.canGoBack) { backEvents ->
        predictiveBackGestureActive = true
        try {
            backEvents.collect { event ->
                predictiveBackEdge = event.swipeEdge
                predictiveBackProgress.snapTo(event.progress.coerceIn(0f, 1f))
            }
            navigateBack()
        } catch (_: CancellationException) {
            predictiveBackProgress.animateTo(0f, animationSpec = tween(durationMillis = 180))
        } finally {
            predictiveBackGestureActive = false
            predictiveBackProgress.snapTo(0f)
        }
    }

    suspend fun refreshConversations() {
        val activeId = activeConversation?.id
        conversations = conversationStore.listMetadata()
        if (activeId != null) activeConversation = conversationStore.get(activeId)
    }

    suspend fun refreshRootAndRootfs(refreshEnvironment: Boolean = false) {
        rootStatus = rootDetector.inspect()
        if (refreshEnvironment) {
            environmentLoading = true
            runCatching { environmentProbe.inspect() }
                .onSuccess { report ->
                    rootStatus = report.rootStatus
                    environmentChecks = report.checks
                    environmentStatus = if (report.checks.all { it.healthy }) "环境检查通过" else "存在需要处理的环境项"
                }
                .onFailure { error -> environmentStatus = error.message ?: error::class.java.simpleName }
            environmentLoading = false
        }
        rootfsInfo = withContext(Dispatchers.IO) { buildRootfsInfo(layout, environmentChecks) }
    }

    suspend fun refreshToolpacks() {
        installedToolpacks = toolpackInstaller.listInstalled()
        selectedToolpack = selectedToolpack?.manifest?.id?.let { id -> installedToolpacks.firstOrNull { it.manifest.id == id } }
    }

    suspend fun refreshStorage() {
        storageInfo = withContext(Dispatchers.IO) {
            val packagesRoot = File(layout.toolpacksRoot, "packages")
            val packageFiles = packagesRoot.listFiles().orEmpty().toList()
            val cacheBytes = directorySize(appContext.cacheDir) + directorySize(layout.tempRoot)
            val unreferencedArchiveBytes = unreferencedToolpackPackages(
                packageFiles = packageFiles,
                referencedPackagePaths = installedToolpacks.mapTo(mutableSetOf(), InstalledToolpack::packagePath),
            ).sumOf(File::length)
            StorageUiInfo(
                workspaceBytes = directorySize(layout.workspacesRoot),
                cacheBytes = cacheBytes,
                toolpackArchiveBytes = packageFiles.filter(File::isFile).sumOf(File::length),
                auditBytes = directorySize(layout.auditRoot),
                sessionBytes = directorySize(layout.sessionsRoot),
                quarantineBytes = directorySize(layout.quarantineRoot),
                reclaimableBytes = cacheBytes + unreferencedArchiveBytes,
            )
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationPermissionPrompted = true
        environmentStatus = if (granted) "Agent 保活通知已授权" else "通知权限未授权；Agent 仍会使用前台服务保活"
        scope.launch { refreshRootAndRootfs(refreshEnvironment = true) }
    }

    fun requestAgentNotificationPermissionIfNeeded() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !notificationPermissionPrompted
        ) {
            notificationPermissionPrompted = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    suspend fun refreshLogs() {
        agentLog = withContext(Dispatchers.IO) { tailText(File(layout.auditRoot, "mobile-agent-events.jsonl"), 90_000) }
        toolLog = withContext(Dispatchers.IO) { tailText(layout.chrootAuditFile, 120_000) }
    }

    LaunchedEffect(Unit) {
        refreshConversations()
        refreshRootAndRootfs()
        refreshToolpacks()
        refreshStorage()
        refreshLogs()
    }

    val latestTasks by rememberUpdatedState(tasks)
    val taskLifecycleKey = tasks.values.sortedBy(MobileAgentTaskSnapshot::conversationId)
        .joinToString { "${it.conversationId}:${it.status.name}:${it.startedAtEpochMillis}" }
    LaunchedEffect(taskLifecycleKey) {
        refreshConversations()
        while (latestTasks.values.any { it.status == MobileAgentTaskStatus.RUNNING }) {
            delay(RUNNING_CONVERSATION_REFRESH_MILLIS)
            refreshConversations()
        }
        refreshLogs()
    }
    LaunchedEffect(searchQuery, conversations.maxOfOrNull(MobileAgentConversation::updatedAtEpochMillis)) {
        val query = searchQuery.trim()
        searchMatchIds = if (query.isBlank()) {
            null
        } else {
            delay(CONVERSATION_SEARCH_DEBOUNCE_MILLIS)
            conversationStore.searchIds(query)
        }
    }
    LaunchedEffect(toolpackRefreshKey) { refreshToolpacks() }
    LaunchedEffect(storageRefreshKey) { refreshStorage() }

    fun openConversation(conversation: MobileAgentConversation) {
        activeConversation = conversation
        pendingAttachments = emptyList()
        input = ""
        uiStatus = null
        scope.launch {
            val loaded = conversationStore.get(conversation.id) ?: return@launch
            if (activeConversation?.id == conversation.id) activeConversation = loaded
        }
    }

    fun newConversation() {
        scope.launch {
            val conversation = conversationStore.create()
            refreshConversations()
            openConversation(conversation)
            navigate(MobileAgentDestination.Conversations)
        }
    }

    LaunchedEffect(routeRequest?.sequence) {
        val request = routeRequest ?: return@LaunchedEffect
        when (val route = request.route) {
            is MobileAgentLaunchRoute.Conversation -> {
                navigation = navigation.navigate(MobileAgentDestination.Conversations)
                conversationStore.get(route.conversationId)?.let(::openConversation)
            }
            MobileAgentLaunchRoute.Terminal -> navigateSettings(AgentSettingsPage.TERMINAL)
        }
        onRouteConsumed()
    }

    fun sendMessage() {
        val config = savedConfig
        if (config == null) {
            navigateSettings(AgentSettingsPage.MODEL)
            configStatus = "请先配置 API"
            return
        }
        val message = input.trim()
        if (message.isBlank() && pendingAttachments.isEmpty()) return
        requestAgentNotificationPermissionIfNeeded()
        scope.launch {
            val conversation = activeConversation ?: conversationStore.create().also {
                activeConversation = it
                refreshConversations()
            }
            val started = taskCoordinator.start(
                conversationId = conversation.id,
                userMessage = message,
                attachments = pendingAttachments,
                config = config,
            )
            if (started) {
                input = ""
                pendingAttachments = emptyList()
                uiStatus = null
                refreshConversations()
            } else {
                uiStatus = "这个会话已有 Agent 任务在运行"
            }
        }
    }

    val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val conversation = activeConversation ?: conversationStore.create().also {
                    activeConversation = it
                    refreshConversations()
                }
                runCatching { attachmentStore.import(conversation.id, uris) }
                    .onSuccess { imported -> pendingAttachments = pendingAttachments + imported }
                    .onFailure { error -> uiStatus = "附件导入失败：${error.message}" }
            }
        }
    }

    val toolpackPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                toolpackStatus = "正在安装并校验工具包…"
                runCatching {
                    val chroot = createToolpackSelfTestEngine()
                    toolpackInstaller.installAndSelfTest(uri, chroot) { progress -> toolpackStatus = progress }
                }
                    .onSuccess { verified ->
                        toolpackStatus = "已安装并通过自检 ${verified.install.manifest.title} ${verified.install.manifest.version}"
                        toolpackRefreshKey += 1
                        refreshRootAndRootfs(refreshEnvironment = true)
                    }
                    .onFailure { error ->
                        toolpackStatus = error.message?.takeIf { it.startsWith("工具包已安装但自检失败") }
                            ?: "安装失败：${error.message}"
                        toolpackRefreshKey += 1
                        refreshRootAndRootfs(refreshEnvironment = true)
                    }
            }
        }
    }

    val rootfsPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val preservedToolpacks = runCatching { toolpackInstaller.listInstalled() }.getOrDefault(emptyList())
                rootfsStatus = if (rootfsInstallMode == RootfsInstallMode.REBUILD) "正在重建 Debian RootFS…" else "正在更新 Debian RootFS…"
                runCatching {
                    ptyManager.close()
                    val result = rootfsInstaller.install(uri) { progress -> rootfsStatus = progress }
                    val chroot = createToolpackSelfTestEngine()
                    preservedToolpacks.forEach { pack ->
                        val packageFile = File(pack.packagePath)
                        if (packageFile.isFile) {
                            rootfsStatus = "正在恢复并自检工具包：${pack.manifest.title}"
                            toolpackInstaller.installAndSelfTest(Uri.fromFile(packageFile), chroot) { progress ->
                                rootfsStatus = "恢复 ${pack.manifest.title}：$progress"
                            }
                        }
                    }
                    result
                }.onSuccess { result ->
                    rootfsStatus = "RootFS 已就绪：${result.manifest.version}"
                    toolpackRefreshKey += 1
                    refreshRootAndRootfs(refreshEnvironment = true)
                }.onFailure { error ->
                    rootfsStatus = "RootFS 操作失败：${error.message}"
                    refreshRootAndRootfs()
                }
            }
        }
    }

    val saveFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { destination ->
        val request = pendingSave
        pendingSave = null
        if (destination != null && request != null) {
            scope.launch {
                runCatching {
                    val source = MobileAgentFileActions.resolve(layout, request.first, request.second.relativePath)
                    withContext(Dispatchers.IO) { MobileAgentFileActions.copyToUri(appContext, source, destination) }
                }.onSuccess { uiStatus = "文件已保存" }
                    .onFailure { uiStatus = "保存失败：${it.message}" }
            }
        }
    }

    renameTarget?.let { target ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名会话") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = { Text("会话名称") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(enabled = renameInput.isNotBlank(), onClick = {
                    scope.launch {
                        runCatching { conversationStore.rename(target.id, renameInput) }
                            .onSuccess { refreshConversations() }
                            .onFailure { uiStatus = it.message }
                        renameTarget = null
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } },
        )
    }

    val approval = approvals.values.minByOrNull(DangerousOperationRequest::createdAtEpochMillis)
    approval?.let { request ->
        DangerousOperationSheet(
            request = request,
            onDecision = { decision -> taskCoordinator.resolveApproval(request.id, decision) },
        )
    }

    val debugInfo = buildDebugInfo(activeConversation, savedConfig?.model, layout.readRootfsState(), activeConversation?.id?.let { tasks[it] })

    val renderDestination: @Composable (MobileAgentDestination) -> Unit = { destination ->
        val destinationTab = when (destination) {
            MobileAgentDestination.Conversations -> MobileAgentMainTab.CONVERSATIONS
            is MobileAgentDestination.Settings -> MobileAgentMainTab.SETTINGS
        }
        val destinationSettingsPage = (destination as? MobileAgentDestination.Settings)?.page ?: AgentSettingsPage.HOME

        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (destinationTab) {
                MobileAgentMainTab.CONVERSATIONS -> MobileAgentConversationPage(
                    conversations = conversations,
                    activeConversation = activeConversation,
                    input = input,
                    onInputChange = { input = it },
                    pendingAttachments = pendingAttachments,
                    searchQuery = searchQuery,
                    searchMatchIds = searchMatchIds,
                    onSearchChange = { searchQuery = it },
                    taskForConversation = { id -> tasks[id] },
                    uiStatus = uiStatus,
                    hasApi = savedConfig != null,
                    onNewConversation = ::newConversation,
                    onOpenConversation = ::openConversation,
                    onAttach = { kind ->
                        val mimeTypes = when (kind) {
                            AgentAttachmentKind.FILE -> arrayOf("*/*")
                            AgentAttachmentKind.APK -> arrayOf("application/vnd.android.package-archive", "application/octet-stream")
                            AgentAttachmentKind.IMAGE -> arrayOf("image/*")
                        }
                        attachmentPicker.launch(mimeTypes)
                    },
                    onRemoveAttachment = { attachment -> pendingAttachments = pendingAttachments.filterNot { it.id == attachment.id } },
                    onSend = ::sendMessage,
                    onStop = { activeConversation?.id?.let(taskCoordinator::stop) },
                    onResume = {
                        val conversationId = activeConversation?.id
                        val config = savedConfig
                        if (conversationId != null && config != null) {
                            scope.launch {
                                runCatching { taskCoordinator.resume(conversationId, config) }
                                    .onSuccess { resumed ->
                                        uiStatus = if (resumed) null else "这个会话已有 Agent 任务在运行"
                                    }
                                    .onFailure { error -> uiStatus = "无法继续任务：${error.message}" }
                            }
                        }
                    },
                    onRename = { conversation -> renameTarget = conversation; renameInput = conversation.title },
                    onDelete = { conversation ->
                        scope.launch {
                            runCatching { conversationStore.delete(conversation.id) }
                                .onSuccess {
                                    if (activeConversation?.id == conversation.id) activeConversation = null
                                    refreshConversations()
                                    storageRefreshKey += 1
                                }
                                .onFailure { uiStatus = it.message }
                        }
                    },
                    onOpenApiSettings = { navigateSettings(AgentSettingsPage.MODEL) },
                    onOpenFile = { file ->
                        val conversationId = activeConversation?.id
                        if (conversationId != null) {
                            runCatching { MobileAgentFileActions.resolve(layout, conversationId, file.relativePath) }
                                .onSuccess { source -> MobileAgentFileActions.open(appContext, source, file.mimeType) }
                                .onFailure { uiStatus = "打开失败：${it.message}" }
                        }
                    },
                    onShareFile = { file ->
                        val conversationId = activeConversation?.id
                        if (conversationId != null) {
                            runCatching { MobileAgentFileActions.resolve(layout, conversationId, file.relativePath) }
                                .onSuccess { source -> MobileAgentFileActions.share(appContext, source, file.mimeType) }
                                .onFailure { uiStatus = "分享失败：${it.message}" }
                        }
                    },
                    onSaveFile = { file ->
                        val conversationId = activeConversation?.id
                        if (conversationId != null) {
                            pendingSave = conversationId to file
                            saveFileLauncher.launch(file.displayName)
                        }
                    },
                )

                MobileAgentMainTab.SETTINGS -> MobileAgentSettingsRouter(
                    page = destinationSettingsPage,
                    onPageChange = ::navigateSettings,
                    onBack = ::navigateBack,
                    activeConfig = savedConfig,
                    providers = providerCatalog.providers,
                    editingProviderId = editingProviderId,
                    providerName = providerNameInput,
                    protocol = providerProtocol,
                    baseUrl = baseUrlInput,
                    model = modelInput,
                    apiKey = apiKeyInput,
                    availableModels = availableModels,
                    configBusy = configBusy,
                    onSelectProvider = { provider ->
                        runCatching { configStore.setActiveProvider(provider.id) }
                            .onSuccess {
                                providerCatalog = configStore.loadCatalog()
                                loadProviderDraft(provider)
                                configStatus = "当前使用：${provider.name}"
                            }
                            .onFailure { configStatus = it.message }
                    },
                    onNewProvider = ::newProviderDraft,
                    onProviderNameChange = { providerNameInput = it.take(64) },
                    onProtocolChange = { selected ->
                        providerProtocol = selected
                        availableModels = emptyList()
                        configStatus = null
                    },
                    onBaseUrlChange = { baseUrlInput = it },
                    onModelChange = { modelInput = it },
                    onApiKeyChange = { apiKeyInput = it },
                    configStatus = configStatus,
                    onSaveConfig = {
                        runCatching {
                            val config = draftProviderConfig().validated()
                            configStore.saveProvider(config, makeActive = true)
                            config
                        }.onSuccess { config ->
                            providerCatalog = configStore.loadCatalog()
                            loadProviderDraft(config)
                            configStatus = "供应商已保存并设为当前使用"
                        }.onFailure { configStatus = it.message }
                    },
                    onDeleteProvider = {
                        runCatching { configStore.deleteProvider(editingProviderId) }
                            .onSuccess {
                                providerCatalog = configStore.loadCatalog()
                                providerCatalog.activeProvider?.let(::loadProviderDraft) ?: newProviderDraft()
                                configStatus = "供应商已删除"
                            }
                            .onFailure { configStatus = it.message }
                    },
                    onFetchModels = {
                        scope.launch {
                            configBusy = true
                            configStatus = "正在获取模型列表…"
                            runCatching { providerProbe.fetchModels(draftProviderConfig()) }
                                .onSuccess { models ->
                                    availableModels = models
                                    if (modelInput.isBlank() && models.isNotEmpty()) modelInput = models.first()
                                    configStatus = if (models.isEmpty()) {
                                        "连接成功，但服务返回了空模型列表；仍可手工输入模型"
                                    } else {
                                        "已获取 ${models.size} 个模型"
                                    }
                                }
                                .onFailure { configStatus = "获取模型失败：${it.message}；仍可手工输入模型" }
                            configBusy = false
                        }
                    },
                    onTestConnectivity = {
                        scope.launch {
                            configBusy = true
                            configStatus = "正在测试连接与鉴权…"
                            runCatching { providerProbe.testConnectivity(draftProviderConfig()) }
                                .onSuccess { result -> configStatus = "联通成功（HTTP ${result.statusCode}），未发送推理请求" }
                                .onFailure { configStatus = "联通失败：${it.message}" }
                            configBusy = false
                        }
                    },
                    onTestHi = {
                        scope.launch {
                            configBusy = true
                            configStatus = "正在发送 hi…"
                            runCatching { providerProbe.testHi(draftProviderConfig()) }
                                .onSuccess { result -> configStatus = "hi 测试成功：${result.responseText}" }
                                .onFailure { configStatus = "hi 测试失败：${it.message}" }
                            configBusy = false
                        }
                    },
                    preferences = agentPreferences,
                    systemPrompt = systemPromptInput,
                    maxToolIterations = maxToolIterationsInput,
                    onSystemPromptChange = { systemPromptInput = it.take(MobileAgentPreferences.MAX_SYSTEM_PROMPT_CHARS) },
                    onMaxToolIterationsChange = { maxToolIterationsInput = it },
                    onCompressionChange = { enabled -> agentPreferences = agentPreferences.copy(contextCompressionEnabled = enabled) },
                    onSaveAgentPreferences = {
                        val iterations = maxToolIterationsInput.toIntOrNull()?.coerceIn(MobileAgentPreferences.MIN_TOOL_ITERATIONS, MobileAgentPreferences.MAX_TOOL_ITERATIONS)
                            ?: MobileAgentPreferences().maxToolIterations
                        agentPreferences = agentPreferences.copy(customSystemPrompt = systemPromptInput, maxToolIterations = iterations).validated()
                        preferencesStore.save(agentPreferences)
                        maxToolIterationsInput = agentPreferences.maxToolIterations.toString()
                    },
                    rootGranted = rootStatus?.isRootGranted == true,
                    rootProvider = rootStatus?.provider?.name ?: "UNKNOWN",
                    rootfsInfo = rootfsInfo,
                    environmentChecks = environmentChecks,
                    environmentLoading = environmentLoading,
                    environmentStatus = environmentStatus,
                    onRefreshEnvironment = { scope.launch { refreshRootAndRootfs(refreshEnvironment = true) } },
                    onRepairEnvironment = { check ->
                        when (check.id) {
                            "rootfs" -> navigateSettings(AgentSettingsPage.ROOTFS)
                            "notifications" -> notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    rootfsStatus = rootfsStatus,
                    onRootfsStart = {
                        scope.launch {
                            rootfsStatus = "正在检查 Debian 环境…"
                            refreshRootAndRootfs(refreshEnvironment = true)
                            rootfsStatus = if (environmentChecks.filter { it.label in setOf("bash", "Python") }.all { it.healthy }) "Debian 环境可用" else "Debian 环境存在异常"
                        }
                    },
                    onRootfsStop = { scope.launch { runCatching { ptyManager.close() }; rootfsStatus = "已关闭当前 Debian PTY" } },
                    onRootfsUpdate = { rootfsInstallMode = RootfsInstallMode.UPDATE; rootfsPicker.launch(arrayOf("application/zip", "application/octet-stream")) },
                    onRootfsRebuild = { rootfsInstallMode = RootfsInstallMode.REBUILD; rootfsPicker.launch(arrayOf("application/zip", "application/octet-stream")) },
                    installedToolpacks = installedToolpacks,
                    selectedToolpack = selectedToolpack,
                    toolpackStatus = toolpackStatus,
                    onSelectToolpack = { selectedToolpack = it },
                    onInstallToolpack = { selectedToolpack = null; toolpackPicker.launch(arrayOf("application/zip", "application/octet-stream")) },
                    onUpdateToolpack = { toolpackPicker.launch(arrayOf("application/zip", "application/octet-stream")) },
                    onUninstallToolpack = { pack ->
                        scope.launch {
                            toolpackStatus = "正在卸载 ${pack.manifest.title}"
                            runCatching { toolpackInstaller.uninstall(pack.manifest.id) { toolpackStatus = it } }
                                .onSuccess { selectedToolpack = null; navigateBack(); toolpackRefreshKey += 1 }
                                .onFailure { toolpackStatus = "卸载失败：${it.message}" }
                        }
                    },
                    onDangerousConfirmationChange = { enabled ->
                        agentPreferences = agentPreferences.copy(dangerousOperationConfirmation = enabled)
                        preferencesStore.save(agentPreferences)
                    },
                    onSystemWritePolicyChange = { policy ->
                        agentPreferences = agentPreferences.copy(systemWritePolicy = policy)
                        preferencesStore.save(agentPreferences)
                    },
                    onClearAlwaysAllowed = {
                        preferencesStore.clearAlwaysAllowedCategories()
                        agentPreferences = preferencesStore.load()
                    },
                    storage = storageInfo,
                    onRefreshStorage = { storageRefreshKey += 1 },
                    onClearCache = {
                        if (tasks.values.any { it.status == MobileAgentTaskStatus.RUNNING }) {
                            uiStatus = "Agent 运行时不清理缓存"
                        } else {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    appContext.cacheDir.listFiles().orEmpty().forEach(File::deleteRecursively)
                                    layout.tempRoot.listFiles().orEmpty().forEach(File::deleteRecursively)
                                }
                                storageRefreshKey += 1
                            }
                        }
                    },
                    debugInfo = debugInfo,
                    agentLog = agentLog,
                    toolLog = toolLog,
                )
            }
        }

        NavigationBar {
            MobileAgentMainTab.entries.forEach { item ->
                NavigationBarItem(
                    selected = destinationTab == item,
                    onClick = {
                        when (item) {
                            MobileAgentMainTab.CONVERSATIONS -> navigate(MobileAgentDestination.Conversations)
                            MobileAgentMainTab.SETTINGS -> navigateSettings(AgentSettingsPage.HOME)
                        }
                        if (item == MobileAgentMainTab.SETTINGS) {
                            scope.launch { refreshLogs(); refreshStorage() }
                        }
                    },
                    icon = {
                        Icon(
                            imageVector = when (item) {
                                MobileAgentMainTab.CONVERSATIONS -> Icons.AutoMirrored.Filled.List
                                MobileAgentMainTab.SETTINGS -> Icons.Default.Settings
                            },
                            contentDescription = item.label,
                        )
                    },
                    label = { Text(item.label) },
                )
            }
        }
    }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        if (predictiveBackGestureActive) {
            navigation.previous?.let { previousDestination ->
                Box(
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        val progress = predictiveBackProgress.value
                        val direction = if (predictiveBackEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f
                        translationX = -direction * size.width * 0.05f * (1f - progress)
                    },
                ) {
                    renderDestination(previousDestination)
                }
            }
        }
        Box(
            modifier = Modifier.fillMaxSize().graphicsLayer {
                val progress = predictiveBackProgress.value
                val direction = if (predictiveBackEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f
                translationX = direction * size.width * progress
            },
        ) {
            renderDestination(currentDestination)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DangerousOperationSheet(
    request: DangerousOperationRequest,
    onDecision: (DangerousOperationDecision) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = { onDecision(DangerousOperationDecision.DENY) }) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("需要确认", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("Agent 请求执行：${request.category.label}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                request.command,
                modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(10.dp)).verticalScroll(rememberScrollState()).padding(10.dp),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
            request.reason?.takeIf(String::isNotBlank)?.let {
                Text("原因：$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { onDecision(DangerousOperationDecision.DENY) }) { Text("拒绝") }
            Button(modifier = Modifier.fillMaxWidth(), onClick = { onDecision(DangerousOperationDecision.ALLOW_ONCE) }) { Text("允许一次") }
            TextButton(modifier = Modifier.fillMaxWidth(), onClick = { onDecision(DangerousOperationDecision.ALWAYS_ALLOW_CATEGORY) }) { Text("始终允许此类操作") }
        }
    }
}

private fun buildRootfsInfo(layout: RuntimeLayout, checks: List<EnvironmentCheckItem>): RootfsUiInfo {
    val manifest = runCatching {
        if (layout.installedRootfsManifestFile.isFile) JSONObject(layout.installedRootfsManifestFile.readText()) else null
    }.getOrNull()
    val localBaseTools = mapOf(
        "bash" to File(layout.rootfsRoot, "bin/bash").exists(),
        "Python" to File(layout.rootfsRoot, "usr/bin/python3").exists(),
        "Git" to File(layout.rootfsRoot, "usr/bin/git").exists(),
        "Java" to File(layout.rootfsRoot, "usr/lib/jvm/java-17-openjdk-arm64/bin/java").exists(),
        "Clang" to File(layout.rootfsRoot, "usr/bin/clang").exists(),
    ).toMutableMap()
    checks.filter { it.label in localBaseTools.keys }.forEach { localBaseTools[it.label] = it.healthy }
    return RootfsUiInfo(
        version = manifest?.optString("version")?.takeIf(String::isNotBlank) ?: layout.readRootfsVersion(),
        architecture = manifest?.optString("architecture")?.takeIf(String::isNotBlank) ?: "arm64",
        state = layout.readRootfsState(),
        path = layout.rootfsRoot.path,
        sizeBytes = directorySize(layout.rootfsRoot),
        baseTools = localBaseTools,
    )
}

private fun buildDebugInfo(
    conversation: MobileAgentConversation?,
    model: String?,
    rootfsState: RuntimeRootfsState,
    task: MobileAgentTaskSnapshot?,
): DebugUiInfo {
    val toolCalls = conversation?.messages.orEmpty().sumOf { message ->
        if (message.role != MobileAgentRole.ASSISTANT || message.toolCallsJson.isNullOrBlank()) 0
        else runCatching { JSONArray(message.toolCallsJson).length() }.getOrDefault(0)
    }
    val chars = conversation?.messages.orEmpty().sumOf { message -> message.content.length + (message.toolCallsJson?.length ?: 0) }
    return DebugUiInfo(
        sessionId = conversation?.id,
        model = model,
        contextCharacters = chars,
        compactionCount = conversation?.compactionCount ?: 0,
        toolCallCount = toolCalls,
        rootfsState = rootfsState,
        taskStatus = task?.let { "${it.status.name} · ${it.stage}" },
    )
}

private fun directorySize(root: File): Long {
    if (!root.exists()) return 0L
    if (root.isFile) return root.length()
    return root.listFiles().orEmpty().sumOf(::directorySize)
}

private fun tailText(file: File, maxBytes: Int): String {
    if (!file.isFile || file.length() <= 0L) return ""
    RandomAccessFile(file, "r").use { random ->
        val length = random.length()
        val start = (length - maxBytes).coerceAtLeast(0L)
        random.seek(start)
        val bytes = ByteArray((length - start).toInt())
        random.readFully(bytes)
        val text = String(bytes, Charsets.UTF_8)
        return if (start == 0L) text else text.substringAfter('\n', text)
    }
}
