package com.luckylca.autocrack.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
            hasApi = savedConfig != null,
            rootGranted = rootGranted,
            rootfsInfo = rootfsInfo,
            toolpackCount = installedToolpacks.size,
            onPageChange = onPageChange,
        )
        AgentSettingsPage.MODEL -> ModelSettings(
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
    hasApi: Boolean,
    rootGranted: Boolean,
    rootfsInfo: RootfsUiInfo,
    toolpackCount: Int,
    onPageChange: (AgentSettingsPage) -> Unit,
) {
    SettingsList(title = "设置") {
        SettingsGroupTitle("模型")
        SettingsRow("模型与 API", if (hasApi) "已配置" else "未配置") { onPageChange(AgentSettingsPage.MODEL) }
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
    SettingsPage("模型", onBack) {
        SettingsValueRow("Provider", "OpenAI Compatible")
        OutlinedTextField(modifier = Modifier.fillMaxWidth(), value = baseUrl, onValueChange = onBaseUrlChange, label = { Text("Base URL") }, singleLine = true)
        OutlinedTextField(modifier = Modifier.fillMaxWidth(), value = model, onValueChange = onModelChange, label = { Text("Model") }, singleLine = true)
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(), value = apiKey, onValueChange = onApiKeyChange,
            label = { Text(if (savedConfig == null) "API Key" else "API Key（留空保持现有值）") }, singleLine = true,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(modifier = Modifier.weight(1f), onClick = onSave) { Text("保存") }
            OutlinedButton(modifier = Modifier.weight(1f), onClick = onClear, enabled = savedConfig != null) { Text("清除") }
        }
        status?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
        SettingsSwitchRow("危险操作确认", "只对明显破坏性 Root 操作弹确认，普通 shell 不打扰", preferences.dangerousOperationConfirmation, onConfirmationChange)
        SettingsGroupTitle("系统文件写入策略")
        SystemWritePolicy.entries.forEach { policy ->
            val label = when (policy) { SystemWritePolicy.ASK -> "询问"; SystemWritePolicy.DENY -> "拒绝"; SystemWritePolicy.ALLOW -> "允许" }
            SettingsRow(label, if (preferences.systemWritePolicy == policy) "✓ 当前策略" else "") { onSystemWritePolicyChange(policy) }
        }
        SettingsValueRow("已永久允许的危险类别", preferences.alwaysAllowedDangerousCategories.size.toString())
        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onClearAlwaysAllowed, enabled = preferences.alwaysAllowedDangerousCategories.isNotEmpty()) { Text("清除“始终允许”记录") }
    }
}

@Composable
private fun StorageSettings(storage: StorageUiInfo, onRefresh: () -> Unit, onClearCache: () -> Unit, onBack: () -> Unit) {
    SettingsPage("存储", onBack) {
        SettingsValueRow("Workspace 占用", formatAgentBytes(storage.workspaceBytes))
        SettingsValueRow("Cache", formatAgentBytes(storage.cacheBytes))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(modifier = Modifier.weight(1f), onClick = onRefresh) { Text("刷新") }
            Button(modifier = Modifier.weight(1f), onClick = onClearCache) { Text("清理缓存") }
        }
        Text("清理缓存不会删除会话、RootFS 或已安装工具包。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        TextButton(onClick = onBack) { Text("‹") }
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
