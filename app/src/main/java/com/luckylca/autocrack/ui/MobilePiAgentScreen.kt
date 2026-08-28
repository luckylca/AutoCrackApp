package com.luckylca.autocrack.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.core.content.ContextCompat
import com.luckylca.autocrack.agent.AgentToolSessionFactory
import com.luckylca.autocrack.agent.LlmProviderConfig
import com.luckylca.autocrack.agent.MobileAgentConversation
import com.luckylca.autocrack.agent.MobileAgentConversationStore
import com.luckylca.autocrack.agent.MobileAgentRole
import com.luckylca.autocrack.agent.MobileAgentRuntimeSession
import com.luckylca.autocrack.agent.OpenAiCompatibleToolClient
import com.luckylca.autocrack.agent.SecureLlmConfigStore
import com.luckylca.autocrack.root.ProcessRootCommandRunner
import com.luckylca.autocrack.root.RootDetector
import com.luckylca.autocrack.root.RootStatus
import com.luckylca.autocrack.runtime.AgentExecutionForegroundService
import com.luckylca.autocrack.runtime.InstalledToolpack
import com.luckylca.autocrack.runtime.RuntimeLayout
import com.luckylca.autocrack.runtime.RuntimeRootfsState
import com.luckylca.autocrack.runtime.ToolpackPackageInstaller
import kotlinx.coroutines.launch

private enum class MobileMainTab(val label: String) {
    CONVERSATIONS("会话"),
    SETTINGS("设置"),
}

private enum class MobileSettingsPage {
    ROOT,
    API,
    PERMISSIONS,
    TOOLPACKS,
}

@Composable
fun LegacyMobilePiAgentScreen() {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val runner = remember { ProcessRootCommandRunner() }
    val rootDetector = remember(runner) { RootDetector(runner) }
    val layout = remember(appContext) { RuntimeLayout(appContext).initialize() }
    val conversationStore = remember(appContext) { MobileAgentConversationStore(appContext) }
    val configStore = remember(appContext) { SecureLlmConfigStore(appContext) }
    val toolClient = remember { OpenAiCompatibleToolClient() }
    val toolFactory = remember(appContext, runner, rootDetector) {
        AgentToolSessionFactory(appContext, runner, rootDetector)
    }
    val toolpackInstaller = remember(appContext, layout) { ToolpackPackageInstaller(appContext, layout) }

    var tab by remember { mutableStateOf(MobileMainTab.CONVERSATIONS) }
    var settingsPage by remember { mutableStateOf(MobileSettingsPage.ROOT) }
    var conversations by remember { mutableStateOf<List<MobileAgentConversation>>(emptyList()) }
    var activeConversation by remember { mutableStateOf<MobileAgentConversation?>(null) }
    var input by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var runStatus by remember { mutableStateOf<String?>(null) }
    var savedConfig by remember { mutableStateOf(configStore.load()) }
    var baseUrlInput by remember { mutableStateOf(savedConfig?.baseUrl.orEmpty()) }
    var modelInput by remember { mutableStateOf(savedConfig?.model.orEmpty()) }
    var apiKeyInput by remember { mutableStateOf("") }
    var configStatus by remember { mutableStateOf<String?>(null) }
    var rootStatus by remember { mutableStateOf<RootStatus?>(null) }
    var rootfsState by remember { mutableStateOf(layout.readRootfsState()) }
    var installedToolpacks by remember { mutableStateOf<List<InstalledToolpack>>(emptyList()) }
    var toolpackStatus by remember { mutableStateOf<String?>(null) }
    var toolpackRefreshKey by remember { mutableIntStateOf(0) }

    suspend fun refreshConversations() {
        conversations = conversationStore.list()
        activeConversation?.let { active ->
            activeConversation = conversations.firstOrNull { it.id == active.id }
        }
    }

    LaunchedEffect(Unit) {
        refreshConversations()
        rootStatus = rootDetector.inspect()
    }

    LaunchedEffect(toolpackRefreshKey) {
        rootfsState = layout.readRootfsState()
        installedToolpacks = toolpackInstaller.listInstalled()
    }

    fun newConversation() {
        scope.launch {
            val conversation = conversationStore.create()
            refreshConversations()
            activeConversation = conversation
            tab = MobileMainTab.CONVERSATIONS
            input = ""
            runStatus = null
        }
    }

    fun sendMessage() {
        val message = input.trim()
        if (message.isBlank() || running) return
        val config = savedConfig
        if (config == null) {
            runStatus = "请先在设置中配置 API"
            tab = MobileMainTab.SETTINGS
            settingsPage = MobileSettingsPage.API
            return
        }

        scope.launch {
            running = true
            runStatus = "准备 Agent"
            var runtimeSession: MobileAgentRuntimeSession? = null
            var leaseId: String? = null
            try {
                val conversation = activeConversation ?: conversationStore.create().also {
                    activeConversation = it
                    refreshConversations()
                }
                val withUser = conversationStore.append(conversation.id, MobileAgentRole.USER, message)
                activeConversation = withUser
                input = ""
                refreshConversations()

                leaseId = AgentExecutionForegroundService.acquire(appContext, "mobile-agent")
                runtimeSession = toolFactory.createMobileAgent(
                    sessionId = conversation.id,
                    knownRootStatus = rootStatus,
                    onStage = { stage -> runStatus = stage },
                )
                val answer = toolClient.completeWithTools(
                    config = config,
                    systemPrompt = buildMobileAgentSystemPrompt(runtimeSession),
                    userPrompt = buildConversationPrompt(withUser),
                    tools = runtimeSession.tools.tools,
                    dispatcher = runtimeSession.tools::dispatch,
                )
                val updated = conversationStore.append(
                    conversation.id,
                    MobileAgentRole.ASSISTANT,
                    answer.content,
                )
                activeConversation = updated
                refreshConversations()
                runStatus = null
            } catch (exception: Exception) {
                runStatus = exception.message ?: exception::class.java.simpleName
            } finally {
                runtimeSession?.tools?.closeSafely()
                leaseId?.let { AgentExecutionForegroundService.release(appContext, it) }
                running = false
            }
        }
    }

    val toolpackPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                toolpackStatus = "正在安装工具包"
                runCatching { toolpackInstaller.install(uri) { progress -> toolpackStatus = progress } }
                    .onSuccess { result ->
                        toolpackStatus = "已安装 ${result.manifest.title}"
                        toolpackRefreshKey += 1
                    }
                    .onFailure { error -> toolpackStatus = "安装失败：${error.message}" }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                MobileMainTab.CONVERSATIONS -> ConversationsSurface(
                    conversations = conversations,
                    activeConversation = activeConversation,
                    input = input,
                    onInputChange = { input = it },
                    running = running,
                    runStatus = runStatus,
                    hasApi = savedConfig != null,
                    onNewConversation = ::newConversation,
                    onOpenConversation = { activeConversation = it },
                    onBackToList = { activeConversation = null },
                    onSend = ::sendMessage,
                    onOpenApiSettings = {
                        tab = MobileMainTab.SETTINGS
                        settingsPage = MobileSettingsPage.API
                    },
                )

                MobileMainTab.SETTINGS -> SettingsSurface(
                    page = settingsPage,
                    onPageChange = { settingsPage = it },
                    savedConfig = savedConfig,
                    baseUrl = baseUrlInput,
                    model = modelInput,
                    apiKey = apiKeyInput,
                    onBaseUrlChange = { baseUrlInput = it },
                    onModelChange = { modelInput = it },
                    onApiKeyChange = { apiKeyInput = it },
                    configStatus = configStatus,
                    onSaveConfig = {
                        runCatching {
                            val key = apiKeyInput.ifBlank {
                                savedConfig?.apiKey ?: error("首次配置必须输入 API Key")
                            }
                            val config = LlmProviderConfig(baseUrlInput, modelInput, key).validated()
                            configStore.save(config)
                            savedConfig = config
                            baseUrlInput = config.baseUrl
                            modelInput = config.model
                            apiKeyInput = ""
                            configStatus = "已保存"
                        }.onFailure { configStatus = it.message ?: "保存失败" }
                    },
                    onClearConfig = {
                        configStore.clear()
                        savedConfig = null
                        baseUrlInput = ""
                        modelInput = ""
                        apiKeyInput = ""
                        configStatus = "已清除"
                    },
                    rootStatus = rootStatus,
                    rootfsState = rootfsState,
                    onRefreshPermissions = {
                        scope.launch {
                            rootStatus = rootDetector.inspect()
                            rootfsState = layout.readRootfsState()
                        }
                    },
                    installedToolpacks = installedToolpacks,
                    toolpackStatus = toolpackStatus,
                    onInstallToolpack = {
                        toolpackPicker.launch(arrayOf("application/zip", "application/octet-stream"))
                    },
                    onUninstallToolpack = { pack ->
                        scope.launch {
                            toolpackStatus = "正在卸载 ${pack.manifest.title}"
                            runCatching { toolpackInstaller.uninstall(pack.manifest.id) }
                                .onSuccess {
                                    toolpackStatus = "已卸载 ${pack.manifest.title}"
                                    toolpackRefreshKey += 1
                                }
                                .onFailure { toolpackStatus = "卸载失败：${it.message}" }
                        }
                    },
                )
            }
        }

        NavigationBar(modifier = Modifier.navigationBarsPadding()) {
            MobileMainTab.entries.forEach { item ->
                NavigationBarItem(
                    selected = tab == item,
                    onClick = {
                        tab = item
                        if (item == MobileMainTab.SETTINGS) settingsPage = MobileSettingsPage.ROOT
                    },
                    icon = { Text(if (item == MobileMainTab.CONVERSATIONS) "聊" else "设") },
                    label = { Text(item.label) },
                )
            }
        }
    }
}

@Composable
private fun ConversationsSurface(
    conversations: List<MobileAgentConversation>,
    activeConversation: MobileAgentConversation?,
    input: String,
    onInputChange: (String) -> Unit,
    running: Boolean,
    runStatus: String?,
    hasApi: Boolean,
    onNewConversation: () -> Unit,
    onOpenConversation: (MobileAgentConversation) -> Unit,
    onBackToList: () -> Unit,
    onSend: () -> Unit,
    onOpenApiSettings: () -> Unit,
) {
    if (activeConversation == null) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(onClick = onNewConversation) { Text("＋") }
                Column(modifier = Modifier.weight(1f)) {
                    Text("会话", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Mobile Agent", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (conversations.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("还没有会话", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("点击左上角 ＋ 开始一个新会话。直接告诉 Agent 你需要它做什么。")
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(conversations, key = MobileAgentConversation::id) { conversation ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onOpenConversation(conversation) },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    conversation.title,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    conversation.messages.lastOrNull()?.content ?: "空会话",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onNewConversation, enabled = !running) { Text("＋") }
            OutlinedButton(onClick = onBackToList, enabled = !running) { Text("会话") }
            Text(
                activeConversation.title,
                modifier = Modifier.weight(1f).padding(top = 12.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold,
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(activeConversation.messages, key = { it.id }) { message ->
                val user = message.role == MobileAgentRole.USER
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (user) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainer
                        },
                    ),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            if (user) "你" else "Agent",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(message.content)
                    }
                }
            }
            if (running) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            Text(runStatus ?: "Agent 正在工作")
                        }
                    }
                }
            } else if (!runStatus.isNullOrBlank()) {
                item { Text(runStatus, color = MaterialTheme.colorScheme.error) }
            }
        }

        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            if (!hasApi) {
                OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onOpenApiSettings) {
                    Text("先配置 API")
                }
                Spacer(Modifier.height(8.dp))
            }
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = input,
                onValueChange = onInputChange,
                placeholder = { Text("告诉 Agent 你需要它做什么…") },
                minLines = 2,
                maxLines = 6,
                enabled = !running,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onSend,
                enabled = !running && input.isNotBlank(),
            ) {
                Text("发送")
            }
        }
    }
}

@Composable
private fun SettingsSurface(
    page: MobileSettingsPage,
    onPageChange: (MobileSettingsPage) -> Unit,
    savedConfig: LlmProviderConfig?,
    baseUrl: String,
    model: String,
    apiKey: String,
    onBaseUrlChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    configStatus: String?,
    onSaveConfig: () -> Unit,
    onClearConfig: () -> Unit,
    rootStatus: RootStatus?,
    rootfsState: RuntimeRootfsState,
    onRefreshPermissions: () -> Unit,
    installedToolpacks: List<InstalledToolpack>,
    toolpackStatus: String?,
    onInstallToolpack: () -> Unit,
    onUninstallToolpack: (InstalledToolpack) -> Unit,
) {
    when (page) {
        MobileSettingsPage.ROOT -> SettingsHome(
            hasApi = savedConfig != null,
            toolpackCount = installedToolpacks.size,
            onPageChange = onPageChange,
        )
        MobileSettingsPage.API -> ApiSettingsPage(
            savedConfig = savedConfig,
            baseUrl = baseUrl,
            model = model,
            apiKey = apiKey,
            onBaseUrlChange = onBaseUrlChange,
            onModelChange = onModelChange,
            onApiKeyChange = onApiKeyChange,
            status = configStatus,
            onSave = onSaveConfig,
            onClear = onClearConfig,
            onBack = { onPageChange(MobileSettingsPage.ROOT) },
        )
        MobileSettingsPage.PERMISSIONS -> PermissionSettingsPage(
            rootStatus = rootStatus,
            rootfsState = rootfsState,
            onRefresh = onRefreshPermissions,
            onBack = { onPageChange(MobileSettingsPage.ROOT) },
        )
        MobileSettingsPage.TOOLPACKS -> ToolpackSettingsPage(
            installed = installedToolpacks,
            status = toolpackStatus,
            onInstall = onInstallToolpack,
            onUninstall = onUninstallToolpack,
            onBack = { onPageChange(MobileSettingsPage.ROOT) },
        )
    }
}

@Composable
private fun SettingsHome(
    hasApi: Boolean,
    toolpackCount: Int,
    onPageChange: (MobileSettingsPage) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Spacer(Modifier.height(4.dp))
            Text("设置", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Agent 配置与本机能力", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { SettingsEntry("API", if (hasApi) "已配置" else "未配置") { onPageChange(MobileSettingsPage.API) } }
        item { SettingsEntry("权限检查", "Root、Rootfs、通知、网络") { onPageChange(MobileSettingsPage.PERMISSIONS) } }
        item { SettingsEntry("工具包", "已安装 $toolpackCount 个") { onPageChange(MobileSettingsPage.TOOLPACKS) } }
    }
}

@Composable
private fun SettingsEntry(title: String, subtitle: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ApiSettingsPage(
    savedConfig: LlmProviderConfig?,
    baseUrl: String,
    model: String,
    apiKey: String,
    onBaseUrlChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    status: String?,
    onSave: () -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { SettingsHeader("API", onBack) }
        item {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = baseUrl,
                onValueChange = onBaseUrlChange,
                label = { Text("Base URL") },
                singleLine = true,
            )
        }
        item {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = model,
                onValueChange = onModelChange,
                label = { Text("模型") },
                singleLine = true,
            )
        }
        item {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = apiKey,
                onValueChange = onApiKeyChange,
                label = { Text(if (savedConfig == null) "API Key" else "API Key（留空保持原值）") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
            )
        }
        item { Button(modifier = Modifier.fillMaxWidth(), onClick = onSave) { Text("保存") } }
        item {
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onClear,
                enabled = savedConfig != null,
            ) {
                Text("清除")
            }
        }
        status?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
    }
}

@Composable
private fun PermissionSettingsPage(
    rootStatus: RootStatus?,
    rootfsState: RuntimeRootfsState,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var notificationGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationGranted = granted
    }
    val internetGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.INTERNET,
    ) == PackageManager.PERMISSION_GRANTED

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { SettingsHeader("权限检查", onBack) }
        item {
            PermissionCard(
                "Root",
                rootStatus?.isRootGranted == true,
                rootStatus?.diagnostic ?: rootStatus?.provider?.name.orEmpty(),
            )
        }
        item { PermissionCard("Debian Rootfs", rootfsState == RuntimeRootfsState.INSTALLED, rootfsState.name) }
        item { PermissionCard("网络", internetGranted, if (internetGranted) "INTERNET 已可用" else "INTERNET 不可用") }
        item {
            PermissionCard("通知", notificationGranted, if (notificationGranted) "已允许" else "未允许")
            if (!notificationGranted && Build.VERSION.SDK_INT >= 33) {
                Spacer(Modifier.height(6.dp))
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                ) {
                    Text("请求通知权限")
                }
            }
        }
        item { OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onRefresh) { Text("重新检查") } }
    }
}

@Composable
private fun PermissionCard(title: String, ok: Boolean, detail: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("${if (ok) "✓" else "!"} $title", fontWeight = FontWeight.SemiBold)
            if (detail.isNotBlank()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ToolpackSettingsPage(
    installed: List<InstalledToolpack>,
    status: String?,
    onInstall: () -> Unit,
    onUninstall: (InstalledToolpack) -> Unit,
    onBack: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { SettingsHeader("工具包", onBack) }
        item {
            Text(
                "安装工具包后，它提供的 CLI 命令会自动加入下一次 Agent 运行的可用能力。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Button(modifier = Modifier.fillMaxWidth(), onClick = onInstall) { Text("安装工具包") }
        }
        status?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
        if (installed.isEmpty()) {
            item { Text("还没有安装工具包。") }
        } else {
            items(installed, key = { it.manifest.id }) { pack ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(pack.manifest.title, fontWeight = FontWeight.SemiBold)
                        Text(pack.manifest.version, style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider()
                        Text(
                            "命令：${pack.manifest.commands.joinToString { it.name }}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onUninstall(pack) },
                        ) {
                            Text("卸载")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsHeader(title: String, onBack: () -> Unit) {
    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedButton(onClick = onBack) { Text("←") }
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

private fun buildMobileAgentSystemPrompt(runtime: MobileAgentRuntimeSession): String {
    val commandLines = if (runtime.installedToolpacks.isEmpty()) {
        "- 当前没有安装额外工具包"
    } else {
        runtime.installedToolpacks.joinToString("\n") { pack ->
            "- ${pack.manifest.title} ${pack.manifest.version}: ${pack.manifest.commands.joinToString { it.name }}"
        }
    }
    return """
        你是运行在 Android 手机上的通用自主 Agent。
        你可以使用 exec_bash、read_file、write_file、kill_process 四个动作原语。
        exec_bash 在受管 Debian rootfs 的当前会话 workspace 中执行 Bash；需要复杂处理时可以自己写 shell 或 Python 脚本。

        当前会话 workspace：${runtime.workspacePath}
        当前已安装工具包及其命令：
        $commandLines

        使用工具完成任务后，用清晰的自然语言向用户汇报结果。遇到信息不足时，可以先用工具调查；确实需要用户提供外部信息时再询问。
    """.trimIndent()
}

private fun buildConversationPrompt(conversation: MobileAgentConversation): String {
    val transcript = conversation.messages.takeLast(MAX_CONTEXT_MESSAGES).joinToString("\n\n") { message ->
        val role = if (message.role == MobileAgentRole.USER) "用户" else "Agent"
        "$role：${message.content.take(MAX_MESSAGE_CHARS)}"
    }
    return """
        下面是当前会话历史。继续这个会话并完成最后一条用户请求。

        $transcript
    """.trimIndent().take(MAX_CONTEXT_CHARS)
}

private const val MAX_CONTEXT_MESSAGES = 24
private const val MAX_MESSAGE_CHARS = 4_000
private const val MAX_CONTEXT_CHARS = 56_000
