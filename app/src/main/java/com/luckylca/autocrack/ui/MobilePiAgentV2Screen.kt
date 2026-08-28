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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.luckylca.autocrack.agent.LlmProviderConfig
import com.luckylca.autocrack.agent.MobileAgentAttachment
import com.luckylca.autocrack.agent.MobileAgentAttachmentStore
import com.luckylca.autocrack.agent.MobileAgentConversation
import com.luckylca.autocrack.agent.MobileAgentConversationStore
import com.luckylca.autocrack.agent.MobileAgentRole
import com.luckylca.autocrack.agent.MobileAgentTaskCoordinator
import com.luckylca.autocrack.agent.MobileAgentTaskSnapshot
import com.luckylca.autocrack.agent.MobileAgentTaskStatus
import com.luckylca.autocrack.agent.SecureLlmConfigStore
import com.luckylca.autocrack.root.ProcessRootCommandRunner
import com.luckylca.autocrack.root.RootDetector
import com.luckylca.autocrack.root.RootStatus
import com.luckylca.autocrack.runtime.InstalledToolpack
import com.luckylca.autocrack.runtime.RuntimeLayout
import com.luckylca.autocrack.runtime.RuntimeRootfsState
import com.luckylca.autocrack.runtime.ToolpackPackageInstaller
import kotlinx.coroutines.launch

private enum class MobileAgentMainTabV2(val label: String) {
    CONVERSATIONS("会话"),
    SETTINGS("设置"),
}

private enum class MobileAgentSettingsPageV2 {
    ROOT,
    API,
    PERMISSIONS,
    TOOLPACKS,
}

@Composable
fun MobilePiAgentScreen() {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val layout = remember(appContext) { RuntimeLayout(appContext).initialize() }
    val conversationStore = remember(appContext) { MobileAgentConversationStore(appContext) }
    val attachmentStore = remember(appContext) { MobileAgentAttachmentStore(appContext) }
    val configStore = remember(appContext) { SecureLlmConfigStore(appContext) }
    val taskCoordinator = remember(appContext) { MobileAgentTaskCoordinator.get(appContext) }
    val tasks by taskCoordinator.tasks.collectAsState()
    val runner = remember { ProcessRootCommandRunner() }
    val rootDetector = remember(runner) { RootDetector(runner) }
    val toolpackInstaller = remember(appContext, layout) { ToolpackPackageInstaller(appContext, layout) }

    var tab by remember { mutableStateOf(MobileAgentMainTabV2.CONVERSATIONS) }
    var settingsPage by remember { mutableStateOf(MobileAgentSettingsPageV2.ROOT) }
    var conversations by remember { mutableStateOf<List<MobileAgentConversation>>(emptyList()) }
    var activeConversation by remember { mutableStateOf<MobileAgentConversation?>(null) }
    var input by remember { mutableStateOf("") }
    var pendingAttachments by remember { mutableStateOf<List<MobileAgentAttachment>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<MobileAgentConversation?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var uiStatus by remember { mutableStateOf<String?>(null) }

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

    val taskStatusKey = tasks.values
        .sortedBy(MobileAgentTaskSnapshot::conversationId)
        .joinToString { "${it.conversationId}:${it.status.name}" }
    LaunchedEffect(taskStatusKey) {
        refreshConversations()
    }

    LaunchedEffect(toolpackRefreshKey) {
        rootfsState = layout.readRootfsState()
        installedToolpacks = toolpackInstaller.listInstalled()
    }

    fun openConversation(conversation: MobileAgentConversation) {
        activeConversation = conversation
        pendingAttachments = emptyList()
        input = ""
        uiStatus = null
    }

    fun newConversation() {
        scope.launch {
            val conversation = conversationStore.create()
            refreshConversations()
            openConversation(conversation)
            tab = MobileAgentMainTabV2.CONVERSATIONS
        }
    }

    fun sendMessage() {
        val config = savedConfig
        if (config == null) {
            tab = MobileAgentMainTabV2.SETTINGS
            settingsPage = MobileAgentSettingsPageV2.API
            configStatus = "请先配置 API"
            return
        }
        val message = input.trim()
        if (message.isBlank() && pendingAttachments.isEmpty()) return
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

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名会话") },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = { Text("会话名称") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = renameInput.isNotBlank(),
                    onClick = {
                        scope.launch {
                            runCatching { conversationStore.rename(target.id, renameInput) }
                                .onSuccess { refreshConversations() }
                                .onFailure { uiStatus = it.message }
                            renameTarget = null
                        }
                    },
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } },
        )
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                MobileAgentMainTabV2.CONVERSATIONS -> MobileAgentConversationsV2(
                    conversations = conversations,
                    activeConversation = activeConversation,
                    input = input,
                    onInputChange = { input = it },
                    pendingAttachments = pendingAttachments,
                    searchQuery = searchQuery,
                    onSearchChange = { searchQuery = it },
                    taskForConversation = { id -> tasks[id] },
                    uiStatus = uiStatus,
                    hasApi = savedConfig != null,
                    onNewConversation = ::newConversation,
                    onOpenConversation = ::openConversation,
                    onBackToList = {
                        activeConversation = null
                        pendingAttachments = emptyList()
                        input = ""
                    },
                    onAttach = { attachmentPicker.launch(arrayOf("*/*")) },
                    onRemoveAttachment = { attachment ->
                        pendingAttachments = pendingAttachments.filterNot { it.id == attachment.id }
                    },
                    onSend = ::sendMessage,
                    onStop = { activeConversation?.id?.let(taskCoordinator::stop) },
                    onRename = { conversation ->
                        renameTarget = conversation
                        renameInput = conversation.title
                    },
                    onDelete = { conversation ->
                        scope.launch {
                            runCatching { conversationStore.delete(conversation.id) }
                                .onSuccess {
                                    if (activeConversation?.id == conversation.id) activeConversation = null
                                    refreshConversations()
                                }
                                .onFailure { uiStatus = it.message }
                        }
                    },
                    onOpenApiSettings = {
                        tab = MobileAgentMainTabV2.SETTINGS
                        settingsPage = MobileAgentSettingsPageV2.API
                    },
                )

                MobileAgentMainTabV2.SETTINGS -> MobileAgentSettingsV2(
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
                            val key = apiKeyInput.ifBlank { savedConfig?.apiKey ?: error("首次配置必须输入 API Key") }
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
                    onInstallToolpack = { toolpackPicker.launch(arrayOf("application/zip", "application/octet-stream")) },
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
            MobileAgentMainTabV2.entries.forEach { item ->
                NavigationBarItem(
                    selected = tab == item,
                    onClick = {
                        tab = item
                        if (item == MobileAgentMainTabV2.SETTINGS) settingsPage = MobileAgentSettingsPageV2.ROOT
                    },
                    icon = { Text(if (item == MobileAgentMainTabV2.CONVERSATIONS) "聊" else "设") },
                    label = { Text(item.label) },
                )
            }
        }
    }
}

@Composable
private fun MobileAgentConversationsV2(
    conversations: List<MobileAgentConversation>,
    activeConversation: MobileAgentConversation?,
    input: String,
    onInputChange: (String) -> Unit,
    pendingAttachments: List<MobileAgentAttachment>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    taskForConversation: (String) -> MobileAgentTaskSnapshot?,
    uiStatus: String?,
    hasApi: Boolean,
    onNewConversation: () -> Unit,
    onOpenConversation: (MobileAgentConversation) -> Unit,
    onBackToList: () -> Unit,
    onAttach: () -> Unit,
    onRemoveAttachment: (MobileAgentAttachment) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onRename: (MobileAgentConversation) -> Unit,
    onDelete: (MobileAgentConversation) -> Unit,
    onOpenApiSettings: () -> Unit,
) {
    if (activeConversation == null) {
        val query = searchQuery.trim()
        val filtered = if (query.isBlank()) conversations else conversations.filter { conversation ->
            conversation.title.contains(query, ignoreCase = true) ||
                conversation.messages.any { it.content.contains(query, ignoreCase = true) }
        }
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
            if (conversations.isNotEmpty()) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    value = searchQuery,
                    onValueChange = onSearchChange,
                    placeholder = { Text("搜索会话") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
            }
            if (filtered.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(if (query.isBlank()) "还没有会话" else "没有匹配的会话", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    if (query.isBlank()) Text("点击左上角 ＋ 开始一个新会话。直接告诉 Agent 你需要它做什么。")
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filtered, key = MobileAgentConversation::id) { conversation ->
                        val task = taskForConversation(conversation.id)
                        val running = task?.status == MobileAgentTaskStatus.RUNNING
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onOpenConversation(conversation) },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        conversation.title,
                                        modifier = Modifier.weight(1f),
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (running) Text("运行中", color = MaterialTheme.colorScheme.primary)
                                }
                                val preview = conversation.messages.lastOrNull { it.visibleInConversation }
                                Text(
                                    preview?.content?.ifBlank { preview.attachments.joinToString { it.displayName } } ?: "空会话",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { onRename(conversation) }) { Text("重命名") }
                                    TextButton(onClick = { onDelete(conversation) }, enabled = !running) { Text("删除") }
                                }
                            }
                        }
                    }
                }
            }
        }
        return
    }

    val task = taskForConversation(activeConversation.id)
    val running = task?.status == MobileAgentTaskStatus.RUNNING
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onNewConversation) { Text("＋") }
            OutlinedButton(onClick = onBackToList) { Text("会话") }
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
            items(activeConversation.messages.filter { it.visibleInConversation }, key = { it.id }) { message ->
                val user = message.role == MobileAgentRole.USER
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                    ),
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (user) "你" else "Agent", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        if (message.content.isNotBlank()) Text(message.content)
                        message.attachments.forEach { attachment ->
                            Text(
                                "附件 · ${attachment.displayName} · ${formatBytes(attachment.sizeBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (running) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text(task.stage)
                            task.streamingText.takeIf(String::isNotBlank)?.let { Text(it) }
                        }
                    }
                }
            } else if (task != null && task.status in setOf(
                    MobileAgentTaskStatus.FAILED,
                    MobileAgentTaskStatus.CANCELLED,
                    MobileAgentTaskStatus.INTERRUPTED,
                )
            ) {
                item {
                    Text(
                        listOfNotNull(task.stage, task.error).joinToString(" · "),
                        color = if (task.status == MobileAgentTaskStatus.CANCELLED) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
            uiStatus?.let { status -> item { Text(status, color = MaterialTheme.colorScheme.error) } }
        }

        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            if (!hasApi) {
                OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onOpenApiSettings) { Text("先配置 API") }
                Spacer(Modifier.height(8.dp))
            }
            if (pendingAttachments.isNotEmpty()) {
                pendingAttachments.forEach { attachment ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "${attachment.displayName} · ${formatBytes(attachment.sizeBytes)}",
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(onClick = { onRemoveAttachment(attachment) }, enabled = !running) { Text("移除") }
                    }
                }
                Spacer(Modifier.height(4.dp))
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
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(modifier = Modifier.weight(1f), onClick = onAttach, enabled = !running) { Text("＋ 文件") }
                if (running) {
                    Button(modifier = Modifier.weight(1f), onClick = onStop) { Text("停止") }
                } else {
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = onSend,
                        enabled = hasApi && (input.isNotBlank() || pendingAttachments.isNotEmpty()),
                    ) { Text("发送") }
                }
            }
        }
    }
}

@Composable
private fun MobileAgentSettingsV2(
    page: MobileAgentSettingsPageV2,
    onPageChange: (MobileAgentSettingsPageV2) -> Unit,
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
        MobileAgentSettingsPageV2.ROOT -> MobileAgentSettingsHomeV2(
            hasApi = savedConfig != null,
            toolpackCount = installedToolpacks.size,
            onPageChange = onPageChange,
        )
        MobileAgentSettingsPageV2.API -> MobileAgentApiSettingsV2(
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
            onBack = { onPageChange(MobileAgentSettingsPageV2.ROOT) },
        )
        MobileAgentSettingsPageV2.PERMISSIONS -> MobileAgentPermissionSettingsV2(
            rootStatus = rootStatus,
            rootfsState = rootfsState,
            onRefresh = onRefreshPermissions,
            onBack = { onPageChange(MobileAgentSettingsPageV2.ROOT) },
        )
        MobileAgentSettingsPageV2.TOOLPACKS -> MobileAgentToolpackSettingsV2(
            installed = installedToolpacks,
            status = toolpackStatus,
            onInstall = onInstallToolpack,
            onUninstall = onUninstallToolpack,
            onBack = { onPageChange(MobileAgentSettingsPageV2.ROOT) },
        )
    }
}

@Composable
private fun MobileAgentSettingsHomeV2(
    hasApi: Boolean,
    toolpackCount: Int,
    onPageChange: (MobileAgentSettingsPageV2) -> Unit,
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
        item { MobileAgentSettingsEntryV2("API", if (hasApi) "已配置" else "未配置") { onPageChange(MobileAgentSettingsPageV2.API) } }
        item { MobileAgentSettingsEntryV2("权限检查", "Root、Rootfs、通知、网络") { onPageChange(MobileAgentSettingsPageV2.PERMISSIONS) } }
        item { MobileAgentSettingsEntryV2("工具包", "已安装 $toolpackCount 个") { onPageChange(MobileAgentSettingsPageV2.TOOLPACKS) } }
    }
}

@Composable
private fun MobileAgentSettingsEntryV2(title: String, subtitle: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MobileAgentApiSettingsV2(
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
        item { MobileAgentSettingsHeaderV2("API", onBack) }
        item {
            OutlinedTextField(modifier = Modifier.fillMaxWidth(), value = baseUrl, onValueChange = onBaseUrlChange, label = { Text("Base URL") }, singleLine = true)
        }
        item {
            OutlinedTextField(modifier = Modifier.fillMaxWidth(), value = model, onValueChange = onModelChange, label = { Text("模型") }, singleLine = true)
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
        item { OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onClear, enabled = savedConfig != null) { Text("清除") } }
        status?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
    }
}

@Composable
private fun MobileAgentPermissionSettingsV2(
    rootStatus: RootStatus?,
    rootfsState: RuntimeRootfsState,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var notificationGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationGranted = granted
    }
    val internetGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { MobileAgentSettingsHeaderV2("权限检查", onBack) }
        item { MobileAgentPermissionCardV2("Root", rootStatus?.isRootGranted == true, rootStatus?.diagnostic ?: rootStatus?.provider?.name.orEmpty()) }
        item { MobileAgentPermissionCardV2("Debian Rootfs", rootfsState == RuntimeRootfsState.INSTALLED, rootfsState.name) }
        item { MobileAgentPermissionCardV2("网络", internetGranted, if (internetGranted) "INTERNET 已可用" else "INTERNET 不可用") }
        item {
            MobileAgentPermissionCardV2("通知", notificationGranted, if (notificationGranted) "已允许" else "未允许")
            if (!notificationGranted && Build.VERSION.SDK_INT >= 33) {
                Spacer(Modifier.height(6.dp))
                Button(modifier = Modifier.fillMaxWidth(), onClick = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("请求通知权限") }
            }
        }
        item { OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onRefresh) { Text("重新检查") } }
    }
}

@Composable
private fun MobileAgentPermissionCardV2(title: String, ok: Boolean, detail: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("${if (ok) "✓" else "!"} $title", fontWeight = FontWeight.SemiBold)
            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MobileAgentToolpackSettingsV2(
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
        item { MobileAgentSettingsHeaderV2("工具包", onBack) }
        item {
            Text("安装工具包后，它提供的原生 CLI 会自动加入 Agent 的环境。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Button(modifier = Modifier.fillMaxWidth(), onClick = onInstall) { Text("安装工具包") }
        }
        status?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
        if (installed.isEmpty()) {
            item { Text("还没有安装工具包。") }
        } else {
            items(installed, key = { it.manifest.id }) { pack ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(pack.manifest.title, fontWeight = FontWeight.SemiBold)
                        Text(pack.manifest.version, style = MaterialTheme.typography.bodySmall)
                        pack.manifest.description.takeIf(String::isNotBlank)?.let {
                            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider()
                        pack.manifest.commands.forEach { command ->
                            Text(
                                buildString {
                                    append(command.name)
                                    command.description.takeIf(String::isNotBlank)?.let { append(" — ").append(it) }
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { onUninstall(pack) }) { Text("卸载") }
                    }
                }
            }
        }
    }
}

@Composable
private fun MobileAgentSettingsHeaderV2(title: String, onBack: () -> Unit) {
    Spacer(Modifier.height(4.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = onBack) { Text("←") }
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
