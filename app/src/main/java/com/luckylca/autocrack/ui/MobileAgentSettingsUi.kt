package com.luckylca.autocrack.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.luckylca.autocrack.agent.LlmApiProtocol
import com.luckylca.autocrack.agent.LlmProviderConfig
import com.luckylca.autocrack.agent.MobileAgentConversation
import com.luckylca.autocrack.agent.MobileAgentPreferences
import com.luckylca.autocrack.agent.SystemWritePolicy
import com.luckylca.autocrack.runtime.EnvironmentCheckItem
import com.luckylca.autocrack.runtime.InstalledToolpack
import com.luckylca.autocrack.runtime.RuntimeRootfsState

internal enum class AgentSettingsPage {
    HOME,
    MODEL,
    AGENT,
    ENVIRONMENT,
    PERMISSION_CHECK,
    ROOTFS,
    TOOLPACKS,
    TOOLPACK_DETAIL,
    SAFETY,
    STORAGE,
    ADVANCED,
    DEBUG,
    AGENT_LOGS,
    TOOL_LOGS,
    TERMINAL,
}

internal data class RootfsUiInfo(
    val version: String?,
    val architecture: String,
    val state: RuntimeRootfsState,
    val path: String,
    val sizeBytes: Long,
    val baseTools: Map<String, Boolean>,
)

internal data class StorageUiInfo(
    val workspaceBytes: Long = 0L,
    val cacheBytes: Long = 0L,
    val toolpackArchiveBytes: Long = 0L,
    val auditBytes: Long = 0L,
    val sessionBytes: Long = 0L,
    val quarantineBytes: Long = 0L,
    val reclaimableBytes: Long = 0L,
)

internal data class DebugUiInfo(
    val sessionId: String?,
    val model: String?,
    val contextCharacters: Int,
    val compactionCount: Int,
    val toolCallCount: Int,
    val rootfsState: RuntimeRootfsState,
    val taskStatus: String?,
)

@Composable
internal fun MobileAgentSettingsRouter(
    page: AgentSettingsPage,
    onPageChange: (AgentSettingsPage) -> Unit,
    onBack: () -> Unit,
    activeConfig: LlmProviderConfig?,
    providers: List<LlmProviderConfig>,
    editingProviderId: String,
    providerName: String,
    protocol: LlmApiProtocol,
    baseUrl: String,
    model: String,
    apiKey: String,
    availableModels: List<String>,
    configBusy: Boolean,
    onSelectProvider: (LlmProviderConfig) -> Unit,
    onNewProvider: () -> Unit,
    onProviderNameChange: (String) -> Unit,
    onProtocolChange: (LlmApiProtocol) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    configStatus: String?,
    onSaveConfig: () -> Unit,
    onDeleteProvider: () -> Unit,
    onFetchModels: () -> Unit,
    onTestConnectivity: () -> Unit,
    onTestHi: () -> Unit,
    preferences: MobileAgentPreferences,
    systemPrompt: String,
    maxToolIterations: String,
    onSystemPromptChange: (String) -> Unit,
    onMaxToolIterationsChange: (String) -> Unit,
    onCompressionChange: (Boolean) -> Unit,
    onSaveAgentPreferences: () -> Unit,
    rootGranted: Boolean,
    rootProvider: String,
    rootfsInfo: RootfsUiInfo,
    environmentChecks: List<EnvironmentCheckItem>,
    environmentLoading: Boolean,
    environmentStatus: String?,
    onRefreshEnvironment: () -> Unit,
    onRepairEnvironment: (EnvironmentCheckItem) -> Unit,
    rootfsStatus: String?,
    onRootfsStart: () -> Unit,
    onRootfsStop: () -> Unit,
    onRootfsUpdate: () -> Unit,
    onRootfsRebuild: () -> Unit,
    installedToolpacks: List<InstalledToolpack>,
    selectedToolpack: InstalledToolpack?,
    toolpackStatus: String?,
    onSelectToolpack: (InstalledToolpack) -> Unit,
    onInstallToolpack: () -> Unit,
    onUpdateToolpack: () -> Unit,
    onUninstallToolpack: (InstalledToolpack) -> Unit,
    onDangerousConfirmationChange: (Boolean) -> Unit,
    onSystemWritePolicyChange: (SystemWritePolicy) -> Unit,
    onClearAlwaysAllowed: () -> Unit,
    storage: StorageUiInfo,
    onRefreshStorage: () -> Unit,
    onClearCache: () -> Unit,
    debugInfo: DebugUiInfo,
    agentLog: String,
    toolLog: String,
) {
    when (page) {
        AgentSettingsPage.HOME -> SettingsHome(
            activeConfig = activeConfig,
            rootGranted = rootGranted,
            rootfsInfo = rootfsInfo,
            toolpackCount = installedToolpacks.size,
            onPageChange = onPageChange,
        )
        AgentSettingsPage.MODEL -> ModelSettings(
            providers = providers,
            activeProviderId = activeConfig?.id,
            editingProviderId = editingProviderId,
            providerName = providerName,
            protocol = protocol,
            baseUrl = baseUrl,
            model = model,
            apiKey = apiKey,
            availableModels = availableModels,
            busy = configBusy,
            onSelectProvider = onSelectProvider,
            onNewProvider = onNewProvider,
            onProviderNameChange = onProviderNameChange,
            onProtocolChange = onProtocolChange,
            onBaseUrlChange = onBaseUrlChange,
            onModelChange = onModelChange,
            onApiKeyChange = onApiKeyChange,
            status = configStatus,
            onSave = onSaveConfig,
            onDelete = onDeleteProvider,
            onFetchModels = onFetchModels,
            onTestConnectivity = onTestConnectivity,
            onTestHi = onTestHi,
            onBack = onBack,
        )
        AgentSettingsPage.AGENT -> AgentSettings(
            preferences = preferences,
            systemPrompt = systemPrompt,
            maxToolIterations = maxToolIterations,
            onSystemPromptChange = onSystemPromptChange,
            onMaxToolIterationsChange = onMaxToolIterationsChange,
            onCompressionChange = onCompressionChange,
            onSave = onSaveAgentPreferences,
            onBack = onBack,
        )
        AgentSettingsPage.ENVIRONMENT -> EnvironmentSettings(
            rootGranted = rootGranted,
            rootProvider = rootProvider,
            rootfsInfo = rootfsInfo,
            onPageChange = onPageChange,
            onBack = onBack,
        )
        AgentSettingsPage.PERMISSION_CHECK -> PermissionCheckSettings(
            checks = environmentChecks,
            loading = environmentLoading,
            status = environmentStatus,
            onRefresh = onRefreshEnvironment,
            onRepair = onRepairEnvironment,
            onBack = onBack,
        )
        AgentSettingsPage.ROOTFS -> RootfsSettings(
            info = rootfsInfo,
            status = rootfsStatus,
            onStart = onRootfsStart,
            onStop = onRootfsStop,
            onUpdate = onRootfsUpdate,
            onRebuild = onRootfsRebuild,
            onBack = onBack,
        )
        AgentSettingsPage.TOOLPACKS -> ToolpackSettings(
            installed = installedToolpacks,
            status = toolpackStatus,
            onSelect = { onSelectToolpack(it); onPageChange(AgentSettingsPage.TOOLPACK_DETAIL) },
            onInstall = onInstallToolpack,
            onBack = onBack,
        )
        AgentSettingsPage.TOOLPACK_DETAIL -> ToolpackDetail(
            installed = selectedToolpack,
            status = toolpackStatus,
            onUpdate = onUpdateToolpack,
            onUninstall = onUninstallToolpack,
            onBack = onBack,
        )
        AgentSettingsPage.SAFETY -> SafetySettings(
            preferences = preferences,
            onConfirmationChange = onDangerousConfirmationChange,
            onSystemWritePolicyChange = onSystemWritePolicyChange,
            onClearAlwaysAllowed = onClearAlwaysAllowed,
            onBack = onBack,
        )
        AgentSettingsPage.STORAGE -> StorageSettings(
            storage = storage,
            onRefresh = onRefreshStorage,
            onClearCache = onClearCache,
            onBack = onBack,
        )
        AgentSettingsPage.ADVANCED -> AdvancedSettings(
            onPageChange = onPageChange,
            onBack = onBack,
        )
        AgentSettingsPage.DEBUG -> DebugSettings(debugInfo, onBack = onBack)
        AgentSettingsPage.AGENT_LOGS -> LogSettings("Agent Logs", agentLog, onBack = onBack)
        AgentSettingsPage.TOOL_LOGS -> LogSettings("Tool Logs", toolLog, onBack = onBack)
        AgentSettingsPage.TERMINAL -> MobileAgentTerminalPage(onBack = onBack)
    }
}

@Composable
private fun SettingsHome(
    activeConfig: LlmProviderConfig?,
    rootGranted: Boolean,
    rootfsInfo: RootfsUiInfo,
    toolpackCount: Int,
    onPageChange: (AgentSettingsPage) -> Unit,
) {
    SettingsList(title = "设置") {
        SettingsGroupTitle("模型")
        SettingsRow(
            "模型与 API",
            activeConfig?.let { "${it.name} · ${it.model}" } ?: "未配置",
        ) { onPageChange(AgentSettingsPage.MODEL) }
        SettingsGroupTitle("Agent")
        SettingsRow("Agent 行为", "System Prompt、上下文压缩、Tool Iterations") { onPageChange(AgentSettingsPage.AGENT) }
        SettingsGroupTitle("环境")
        SettingsRow("Root", if (rootGranted) "✓ 正常" else "! 异常") { onPageChange(AgentSettingsPage.ENVIRONMENT) }
        SettingsRow("Debian RootFS", "${rootfsInfo.state.name} · ${rootfsInfo.version ?: "未安装"}") { onPageChange(AgentSettingsPage.ROOTFS) }
        SettingsRow("权限检查", "Root、通知、挂载、Storage、Network、Debian") { onPageChange(AgentSettingsPage.PERMISSION_CHECK) }
        SettingsGroupTitle("工具包")
        SettingsRow("工具包管理", "已安装 $toolpackCount 个") { onPageChange(AgentSettingsPage.TOOLPACKS) }
        SettingsGroupTitle("权限")
        SettingsRow("危险操作与系统写入", "Root 操作确认策略") { onPageChange(AgentSettingsPage.SAFETY) }
        SettingsGroupTitle("存储")
        SettingsRow("Workspace 与 Cache", "查看占用和清理缓存") { onPageChange(AgentSettingsPage.STORAGE) }
        SettingsGroupTitle("高级")
        SettingsRow("Debug、Logs、Terminal", "高级功能不会出现在主会话界面") { onPageChange(AgentSettingsPage.ADVANCED) }
    }
}

@Composable
private fun ModelSettings(
    providers: List<LlmProviderConfig>,
    activeProviderId: String?,
    editingProviderId: String,
    providerName: String,
    protocol: LlmApiProtocol,
    baseUrl: String,
    model: String,
    apiKey: String,
    availableModels: List<String>,
    busy: Boolean,
    onSelectProvider: (LlmProviderConfig) -> Unit,
    onNewProvider: () -> Unit,
    onProviderNameChange: (String) -> Unit,
    onProtocolChange: (LlmApiProtocol) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    status: String?,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onFetchModels: () -> Unit,
    onTestConnectivity: () -> Unit,
    onTestHi: () -> Unit,
    onBack: () -> Unit,
) {
    val savedConfig = providers.firstOrNull { it.id == editingProviderId }
    val activeConfig = providers.firstOrNull { it.id == activeProviderId }
    var modelMenuExpanded by remember(availableModels) { mutableStateOf(false) }
    var deleteConfirmation by remember { mutableStateOf(false) }
    if (deleteConfirmation && savedConfig != null) {
        AlertDialog(
            onDismissRequest = { deleteConfirmation = false },
            title = { Text("删除供应商？") },
            text = { Text("将删除 ${savedConfig.name} 的地址、模型和已保存 API Key。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteConfirmation = false
                        onDelete()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmation = false }) { Text("取消") }
            },
        )
    }
    SettingsPage("模型供应商", onBack) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("供应商", fontWeight = FontWeight.SemiBold)
            Text("${providers.size} 个", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            providers.forEach { provider ->
                FilterChip(
                    selected = provider.id == editingProviderId,
                    onClick = { onSelectProvider(provider) },
                    enabled = !busy,
                    label = {
                        Text(provider.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    trailingIcon = if (provider.id == activeProviderId) {
                        {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "当前使用",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    } else {
                        null
                    },
                )
            }
            OutlinedButton(onClick = onNewProvider, enabled = !busy) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("添加")
            }
        }
        if (providers.isEmpty()) {
            Text("尚未保存供应商", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            activeConfig?.let {
                Text(
                    "当前使用：${it.name} · ${it.model}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        HorizontalDivider()
        Text("连接配置", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = providerName,
            onValueChange = onProviderNameChange,
            label = { Text("供应商名称") },
            singleLine = true,
        )
        Text("接口格式", fontWeight = FontWeight.SemiBold)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            val options = listOf(
                LlmApiProtocol.OPENAI_CHAT to "OpenAI",
                LlmApiProtocol.ANTHROPIC_MESSAGES to "Anthropic",
            )
            options.forEachIndexed { index, (option, label) ->
                SegmentedButton(
                    selected = protocol == option,
                    onClick = { onProtocolChange(option) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    enabled = !busy,
                ) { Text(label) }
            }
        }
        OutlinedTextField(modifier = Modifier.fillMaxWidth(), value = baseUrl, onValueChange = onBaseUrlChange, label = { Text("Base URL") }, singleLine = true)
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = model,
                onValueChange = onModelChange,
                label = { Text("Model") },
                trailingIcon = {
                    TextButton(
                        onClick = { modelMenuExpanded = true },
                        enabled = availableModels.isNotEmpty(),
                    ) { Text("选择") }
                },
                singleLine = true,
            )
            DropdownMenu(
                expanded = modelMenuExpanded,
                onDismissRequest = { modelMenuExpanded = false },
            ) {
                availableModels.forEach { availableModel ->
                    DropdownMenuItem(
                        text = { Text(availableModel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = {
                            onModelChange(availableModel)
                            modelMenuExpanded = false
                        },
                    )
                }
            }
        }
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(), value = apiKey, onValueChange = onApiKeyChange,
            label = { Text(if (savedConfig == null) "API Key" else "API Key（留空保持现有值）") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        Text("模型与测试", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = onFetchModels,
            enabled = !busy,
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (availableModels.isEmpty()) "获取模型列表" else "刷新模型列表（${availableModels.size}）")
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = onTestConnectivity,
                enabled = !busy,
            ) { Text("仅测联通") }
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = onTestHi,
                enabled = !busy,
            ) { Text("发送 hi") }
        }
        if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        status?.let { message ->
            val isError = message.contains("失败") || message.contains("不能为空") || message.contains("无效")
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                shape = RoundedCornerShape(6.dp),
            ) {
                Text(message, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(
            "发送消息时，Agent 的对话内容、附件文本和工具输出可能会传给这里配置的模型服务。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(modifier = Modifier.weight(1f), onClick = onSave, enabled = !busy) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("保存并使用")
            }
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = { deleteConfirmation = true },
                enabled = savedConfig != null && !busy,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("删除")
            }
        }
    }
}

@Composable
private fun AgentSettings(
    preferences: MobileAgentPreferences,
    systemPrompt: String,
    maxToolIterations: String,
    onSystemPromptChange: (String) -> Unit,
    onMaxToolIterationsChange: (String) -> Unit,
    onCompressionChange: (Boolean) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    SettingsPage("Agent", onBack) {
        Text("System Prompt", fontWeight = FontWeight.SemiBold)
        Text("这里写长期自定义指令；运行环境和 Toolpack 能力仍由 App 自动注入。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth().heightIn(min = 130.dp, max = 260.dp),
            value = systemPrompt,
            onValueChange = onSystemPromptChange,
            placeholder = { Text("可留空") },
            minLines = 5,
            maxLines = 12,
        )
        SettingsSwitchRow("Context Compression", "长会话自动压缩较早上下文，完整历史仍保存在本地", preferences.contextCompressionEnabled, onCompressionChange)
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = maxToolIterations,
            onValueChange = { onMaxToolIterationsChange(it.filter(Char::isDigit).take(4)) },
            label = { Text("Tool Iterations 上限") },
            supportingText = { Text("0 = 自动长任务模式；1–2048 = 手动限制。默认 0，不再按 24 轮停止") },
            singleLine = true,
        )
        Button(modifier = Modifier.fillMaxWidth(), onClick = onSave) { Text("保存 Agent 设置") }
    }
}

@Composable
private fun EnvironmentSettings(
    rootGranted: Boolean,
    rootProvider: String,
    rootfsInfo: RootfsUiInfo,
    onPageChange: (AgentSettingsPage) -> Unit,
    onBack: () -> Unit,
) {
    SettingsList(title = "环境", onBack = onBack) {
        SettingsRow("Root", if (rootGranted) "✓ $rootProvider" else "! 未授权") { onPageChange(AgentSettingsPage.PERMISSION_CHECK) }
        SettingsRow("Debian RootFS", "${rootfsInfo.state.name} · ${rootfsInfo.version ?: "无版本"}") { onPageChange(AgentSettingsPage.ROOTFS) }
        SettingsRow("权限检查", "检查通知、su、chroot、mount、/proc、/sys、/dev、网络和基础命令") { onPageChange(AgentSettingsPage.PERMISSION_CHECK) }
    }
}

@Composable
private fun PermissionCheckSettings(
    checks: List<EnvironmentCheckItem>,
    loading: Boolean,
    status: String?,
    onRefresh: () -> Unit,
    onRepair: (EnvironmentCheckItem) -> Unit,
    onBack: () -> Unit,
) {
    SettingsPage("权限检查", onBack) {
        Button(modifier = Modifier.fillMaxWidth(), onClick = onRefresh, enabled = !loading) { Text(if (loading) "检查中…" else "重新检查") }
        if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        checks.filter { it.label in setOf("Root", "su", "通知", "chroot", "mount", "/proc", "/sys", "/dev", "/dev/pts", "Storage", "Network", "Debian RootFS", "bash", "Python") }.forEach { check ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (check.healthy) "✓" else "!", color = if (check.healthy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(check.label)
                    Text(check.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (!check.healthy && check.safelyRepairable) TextButton(onClick = { onRepair(check) }) { Text("修复") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
        }
    }
}

@Composable
private fun RootfsSettings(
    info: RootfsUiInfo,
    status: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onUpdate: () -> Unit,
    onRebuild: () -> Unit,
    onBack: () -> Unit,
) {
    SettingsPage("Debian RootFS", onBack) {
        SettingsValueRow("Debian 版本", info.version ?: "未安装")
        SettingsValueRow("架构", info.architecture)
        SettingsValueRow("运行状态", info.state.name)
        SettingsValueRow("安装位置", info.path)
        SettingsValueRow("占用空间", formatAgentBytes(info.sizeBytes))
        SettingsGroupTitle("基础环境")
        listOf("bash", "Python", "Git", "Java", "Clang").forEach { tool ->
            val ok = info.baseTools[tool] == true
            SettingsValueRow(tool, if (ok) "✓ 可用" else "! 未确认")
        }
        Text("RootFS 按需进入 chroot；“启动”执行一次环境自检，“停止”会关闭当前 Debian PTY。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(modifier = Modifier.weight(1f), onClick = onStart, enabled = info.state == RuntimeRootfsState.INSTALLED) { Text("启动") }
            OutlinedButton(modifier = Modifier.weight(1f), onClick = onStop) { Text("停止") }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(modifier = Modifier.weight(1f), onClick = onUpdate) { Text("更新") }
            OutlinedButton(modifier = Modifier.weight(1f), onClick = onRebuild) { Text("重建") }
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun ToolpackSettings(
    installed: List<InstalledToolpack>,
    status: String?,
    onSelect: (InstalledToolpack) -> Unit,
    onInstall: () -> Unit,
    onBack: () -> Unit,
) {
    SettingsPage("工具包", onBack) {
        Text("工具包安装后，它提供的原生 CLI 会自动进入 Agent 环境；这里不暴露 Tool Schema。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(modifier = Modifier.fillMaxWidth(), onClick = onInstall) { Text("安装工具包") }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (installed.isEmpty()) Text("尚未安装工具包。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        installed.forEach { pack ->
            SettingsRow(
                title = pack.manifest.title,
                subtitle = buildString {
                    append(pack.manifest.version).append(" · 已安装")
                    pack.manifest.description.takeIf(String::isNotBlank)?.let { append("\n").append(it) }
                },
            ) { onSelect(pack) }
        }
    }
}

@Composable
private fun ToolpackDetail(
    installed: InstalledToolpack?,
    status: String?,
    onUpdate: () -> Unit,
    onUninstall: (InstalledToolpack) -> Unit,
    onBack: () -> Unit,
) {
    SettingsPage("工具包详情", onBack) {
        if (installed == null) {
            Text("工具包不存在。")
            return@SettingsPage
        }
        Text(installed.manifest.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        installed.manifest.description.takeIf(String::isNotBlank)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        SettingsValueRow("版本", installed.manifest.version)
        SettingsValueRow("架构", installed.manifest.architecture)
        SettingsValueRow("状态", "已安装")
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(modifier = Modifier.weight(1f), onClick = onUpdate) { Text("更新") }
            OutlinedButton(modifier = Modifier.weight(1f), onClick = { onUninstall(installed) }) { Text("卸载") }
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun SafetySettings(
    preferences: MobileAgentPreferences,
    onConfirmationChange: (Boolean) -> Unit,
    onSystemWritePolicyChange: (SystemWritePolicy) -> Unit,
    onClearAlwaysAllowed: () -> Unit,
    onBack: () -> Unit,
) {
    SettingsPage("权限", onBack) {
        SettingsSwitchRow("危险操作确认", "应用数据变更、挂载、块设备和设备控制会先询问；关闭后等同完整 Root 模式", preferences.dangerousOperationConfirmation, onConfirmationChange)
        SettingsGroupTitle("系统文件写入策略")
        SystemWritePolicy.entries.forEach { policy ->
            val label = when (policy) { SystemWritePolicy.ASK -> "询问"; SystemWritePolicy.DENY -> "拒绝"; SystemWritePolicy.ALLOW -> "允许" }
            SettingsRow(label, if (preferences.systemWritePolicy == policy) "✓ 当前策略" else "") { onSystemWritePolicyChange(policy) }
        }
        Text("“拒绝”始终优先，涵盖系统属性以及 /system、/vendor、/product、/odm、/apex、/proc、/sys 的写操作。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SettingsValueRow("已永久允许的危险类别", preferences.alwaysAllowedDangerousCategories.size.toString())
        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onClearAlwaysAllowed, enabled = preferences.alwaysAllowedDangerousCategories.isNotEmpty()) { Text("清除“始终允许”记录") }
    }
}

@Composable
private fun StorageSettings(storage: StorageUiInfo, onRefresh: () -> Unit, onClearCache: () -> Unit, onBack: () -> Unit) {
    SettingsPage("存储", onBack) {
        SettingsValueRow("Workspace 占用", formatAgentBytes(storage.workspaceBytes))
        SettingsValueRow("工具包归档", formatAgentBytes(storage.toolpackArchiveBytes))
        SettingsValueRow("审计日志", formatAgentBytes(storage.auditBytes))
        SettingsValueRow("会话数据", formatAgentBytes(storage.sessionBytes))
        SettingsValueRow("隔离区", formatAgentBytes(storage.quarantineBytes))
        SettingsValueRow("Cache", formatAgentBytes(storage.cacheBytes))
        SettingsValueRow("可直接回收", formatAgentBytes(storage.reclaimableBytes))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(modifier = Modifier.weight(1f), onClick = onRefresh) { Text("刷新") }
            Button(modifier = Modifier.weight(1f), onClick = onClearCache) { Text("清理缓存") }
        }
        Text("清理缓存不会删除会话、RootFS、已安装工具包、非空 Workspace 或隔离区；后两者只在此展示，需人工确认后处理。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AdvancedSettings(onPageChange: (AgentSettingsPage) -> Unit, onBack: () -> Unit) {
    SettingsList(title = "高级", onBack = onBack) {
        SettingsRow("Debug", "Session、模型、Context、Compaction、Tool Call、RootFS") { onPageChange(AgentSettingsPage.DEBUG) }
        SettingsRow("Agent Logs", "Agent 生命周期和上下文压缩事件") { onPageChange(AgentSettingsPage.AGENT_LOGS) }
        SettingsRow("Tool Logs", "Debian chroot 命令审计") { onPageChange(AgentSettingsPage.TOOL_LOGS) }
        SettingsRow("Terminal", "完整 Debian PTY") { onPageChange(AgentSettingsPage.TERMINAL) }
    }
}

@Composable
private fun DebugSettings(info: DebugUiInfo, onBack: () -> Unit) {
    SettingsPage("Debug", onBack) {
        SettingsValueRow("当前 Session ID", info.sessionId ?: "无")
        SettingsValueRow("当前模型", info.model ?: "未配置")
        SettingsValueRow("Context 字符量", info.contextCharacters.toString())
        SettingsValueRow("Compaction 次数", info.compactionCount.toString())
        SettingsValueRow("Tool Call 数量", info.toolCallCount.toString())
        SettingsValueRow("RootFS", info.rootfsState.name)
        SettingsValueRow("Agent 状态", info.taskStatus ?: "空闲")
    }
}

@Composable
private fun LogSettings(title: String, log: String, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        MobileAgentSettingsHeader(title, onBack)
        Text(
            text = log.ifBlank { "暂无日志" },
            modifier = Modifier.fillMaxSize().padding(12.dp).background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(10.dp)).verticalScroll(rememberScrollState()).padding(10.dp),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
internal fun MobileAgentSettingsHeader(title: String, onBack: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
}

@Composable
private fun SettingsPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        MobileAgentSettingsHeader(title, onBack)
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun SettingsList(title: String, onBack: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (onBack == null) {
            Text(title, modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
        } else {
            MobileAgentSettingsHeader(title, onBack)
        }
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 18.dp), content = content)
    }
}

@Composable
private fun SettingsGroupTitle(text: String) {
    Text(text, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SettingsRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
    HorizontalDivider(modifier = Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
}

@Composable
private fun SettingsValueRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, modifier = Modifier.weight(0.42f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.weight(0.58f), maxLines = 5, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SettingsSwitchRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
