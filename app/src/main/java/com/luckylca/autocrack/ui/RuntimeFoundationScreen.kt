package com.luckylca.autocrack.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.luckylca.autocrack.BuildConfig
import com.luckylca.autocrack.root.ProcessRootCommandRunner
import com.luckylca.autocrack.root.RootDetector
import com.luckylca.autocrack.runtime.KernelSuHostBridge
import com.luckylca.autocrack.runtime.RootShellRuntimeEngine
import com.luckylca.autocrack.runtime.RuntimeHealthReport
import com.luckylca.autocrack.runtime.RuntimeLayout
import com.luckylca.autocrack.runtime.ShellCommandRequest
import com.luckylca.autocrack.runtime.ShellCommandResult
import com.luckylca.autocrack.runtime.WorkspaceFileEntry
import com.luckylca.autocrack.runtime.WorkspaceFileService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun RuntimeFoundationScreen() {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val layout = remember(appContext) { RuntimeLayout(appContext).initialize() }
    val workspace = remember(layout) { layout.createRuntimeWorkspace() }
    val fileService = remember(workspace) { WorkspaceFileService(workspace) }
    val commandRunner = remember { ProcessRootCommandRunner() }
    val rootDetector = remember(commandRunner) { RootDetector(commandRunner) }
    val hostBridge = remember(layout, rootDetector) { KernelSuHostBridge(layout, rootDetector) }
    val shellEngine = remember(layout) { RootShellRuntimeEngine(layout) }
    val scope = rememberCoroutineScope()

    var refreshKey by remember { mutableIntStateOf(0) }
    var healthLoading by remember { mutableStateOf(true) }
    var healthReport by remember { mutableStateOf<RuntimeHealthReport?>(null) }
    var healthError by remember { mutableStateOf<String?>(null) }

    var shellCommand by remember {
        mutableStateOf("id; uname -a; printf '\\nPWD='; pwd; printf '\\nFILES:\\n'; ls -la")
    }
    var timeoutText by remember { mutableStateOf("30000") }
    var shellRunning by remember { mutableStateOf(false) }
    var shellResult by remember { mutableStateOf<ShellCommandResult?>(null) }
    var shellError by remember { mutableStateOf<String?>(null) }

    var filePath by remember { mutableStateOf("runtime-smoke-test.txt") }
    var fileContent by remember {
        mutableStateOf("AutoCrackApp Root Runtime Foundation\nversion=${BuildConfig.VERSION_NAME}\n")
    }
    var fileStatus by remember { mutableStateOf("尚未执行文件操作") }
    var fileEntries by remember { mutableStateOf<List<WorkspaceFileEntry>>(emptyList()) }
    var auditPreview by remember { mutableStateOf("暂无审计记录") }

    suspend fun refreshAudit() {
        auditPreview = withContext(Dispatchers.IO) {
            if (!layout.shellAuditFile.isFile) {
                "暂无审计记录"
            } else {
                layout.shellAuditFile.readLines(Charsets.UTF_8)
                    .takeLast(MAX_AUDIT_LINES)
                    .joinToString("\n")
            }
        }
    }

    suspend fun refreshFiles() {
        fileEntries = fileService.list()
    }

    LaunchedEffect(refreshKey) {
        healthLoading = true
        healthError = null
        healthReport = runCatching { hostBridge.inspectHealth() }
            .onFailure { exception -> healthError = exception.message ?: "运行时健康检查失败" }
            .getOrNull()
        refreshFiles()
        refreshAudit()
        healthLoading = false
    }

    fun runShell() {
        val timeout = timeoutText.toLongOrNull()
        if (timeout == null) {
            shellError = "超时必须是毫秒整数"
            return
        }
        scope.launch {
            shellRunning = true
            shellError = null
            shellResult = null
            try {
                shellResult = shellEngine.execute(
                    ShellCommandRequest(
                        command = shellCommand,
                        workingDirectory = workspace.path,
                        timeoutMillis = timeout,
                        environment = mapOf(
                            "AUTOC_APP_VERSION" to BuildConfig.VERSION_NAME,
                            "AUTOC_WORKSPACE" to workspace.path,
                        ),
                    ),
                )
            } catch (exception: Exception) {
                shellError = exception.message ?: "Root Shell 执行失败"
            } finally {
                shellRunning = false
                refreshAudit()
                refreshFiles()
            }
        }
    }

    fun copyDiagnostics() {
        val report = buildRuntimeDiagnosticReport(
            health = healthReport,
            healthError = healthError,
            shellResult = shellResult,
            shellError = shellError,
            workspacePath = workspace.path,
            fileStatus = fileStatus,
            auditPath = layout.shellAuditFile.path,
        )
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
            ClipData.newPlainText("AutoCrackApp Runtime Foundation 诊断", report),
        )
        Toast.makeText(context, "运行时诊断已复制", Toast.LENGTH_SHORT).show()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Root Runtime Foundation",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "${BuildConfig.VERSION_NAME} · KernelSU 优先 · Shizuku 后续",
                color = MaterialTheme.colorScheme.primary,
            )
        }

        item {
            RuntimeCard("运行时健康状态") {
                when {
                    healthLoading -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("正在检测 Root、架构、chroot 和基础命令")
                    }
                    healthReport != null -> {
                        val report = checkNotNull(healthReport)
                        RuntimeInfoRow("能力模式", report.capabilityMode.name)
                        RuntimeInfoRow("Rootfs", report.rootfsState.name)
                        RuntimeInfoRow("Root 身份", report.rootIdentity ?: "未知")
                        RuntimeInfoRow("架构", report.architecture ?: "未知")
                        RuntimeInfoRow("SELinux", report.selinuxContext ?: "未知")
                        RuntimeInfoRow("Runtime", report.runtimeRoot)
                        RuntimeInfoRow("Workspace", workspace.path)
                        RuntimeInfoRow(
                            "基础命令",
                            report.availableCommands.joinToString().ifBlank { "无" },
                        )
                        if (report.missingCommands.isNotEmpty()) {
                            RuntimeInfoRow("缺失命令", report.missingCommands.joinToString())
                        }
                        report.diagnostics.forEach { diagnostic ->
                            Text(diagnostic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    else -> Text(
                        healthError ?: "健康检查失败",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { refreshKey += 1 },
                    enabled = !healthLoading && !shellRunning,
                ) {
                    Text("重新检测")
                }
            }
        }

        item {
            RuntimeCard("结构化 Root Shell") {
                Text(
                    "这里执行的是宿主 Root Shell。当前仅供人工验证；Agent 接入后，宿主命令会按能力、目标包和确认策略受控。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = shellCommand,
                    onValueChange = { shellCommand = it },
                    label = { Text("Shell 命令") },
                    minLines = 4,
                    maxLines = 10,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = timeoutText,
                    onValueChange = { timeoutText = it.filter(Char::isDigit) },
                    label = { Text("超时（ms）") },
                    singleLine = true,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = ::runShell,
                        enabled = !shellRunning && shellCommand.isNotBlank(),
                    ) {
                        Text("执行")
                    }
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val count = shellEngine.cancelAll()
                            shellError = "已请求取消 $count 个运行中命令"
                        },
                        enabled = shellRunning,
                    ) {
                        Text("取消")
                    }
                }
                if (shellRunning) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("命令运行中；切换页面不会改变审计文件")
                }
                shellError?.let { message ->
                    Text(message, color = MaterialTheme.colorScheme.error)
                }
                shellResult?.let { result -> ShellResultView(result) }
            }
        }

        item {
            RuntimeCard("工作区文件能力") {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = filePath,
                    onValueChange = { filePath = it },
                    label = { Text("工作区相对路径") },
                    singleLine = true,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = fileContent,
                    onValueChange = { fileContent = it },
                    label = { Text("文本内容") },
                    minLines = 3,
                    maxLines = 8,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            scope.launch {
                                fileStatus = runCatching {
                                    val entry = fileService.writeText(filePath, fileContent)
                                    refreshFiles()
                                    "写入成功：${entry.relativePath}，${entry.sizeBytes} B"
                                }.getOrElse { it.message ?: "写入失败" }
                            }
                        },
                    ) {
                        Text("写入")
                    }
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            scope.launch {
                                fileStatus = runCatching {
                                    val text = fileService.readText(filePath)
                                    "读取成功：\n$text"
                                }.getOrElse { it.message ?: "读取失败" }
                            }
                        },
                    ) {
                        Text("读取")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            scope.launch {
                                fileStatus = runCatching {
                                    "SHA-256：${fileService.sha256(filePath)}"
                                }.getOrElse { it.message ?: "计算失败" }
                            }
                        },
                    ) {
                        Text("SHA-256")
                    }
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            scope.launch {
                                fileStatus = runCatching {
                                    val deleted = fileService.delete(filePath)
                                    refreshFiles()
                                    "删除结果：$deleted"
                                }.getOrElse { it.message ?: "删除失败" }
                            }
                        },
                    ) {
                        Text("删除")
                    }
                }
                Text(fileStatus, fontFamily = FontFamily.Monospace)
                HorizontalDivider()
                Text("工作区文件", fontWeight = FontWeight.SemiBold)
                if (fileEntries.isEmpty()) {
                    Text("目录为空")
                } else {
                    fileEntries.forEach { entry ->
                        Text(
                            "${if (entry.directory) "[DIR]" else "[FILE]"} " +
                                "${entry.relativePath} (${entry.sizeBytes} B)",
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }

        item {
            RuntimeCard("命令审计") {
                RuntimeInfoRow("审计文件", layout.shellAuditFile.path)
                Text(
                    auditPreview,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = ::copyDiagnostics,
                ) {
                    Text("复制完整运行时诊断")
                }
            }
        }

        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun ShellResultView(result: ShellCommandResult) {
    HorizontalDivider()
    Text("命令结果", fontWeight = FontWeight.Bold)
    RuntimeInfoRow("请求 ID", result.requestId)
    RuntimeInfoRow("身份", result.identity.name)
    RuntimeInfoRow("退出码", result.exitCode?.toString() ?: "无")
    RuntimeInfoRow("耗时", "${result.durationMillis} ms")
    RuntimeInfoRow("超时 / 取消", "${result.timedOut} / ${result.cancelled}")
    RuntimeInfoRow(
        "输出截断",
        "stdout=${result.stdoutTruncated}, stderr=${result.stderrTruncated}",
    )
    result.failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (result.stdout.isNotBlank()) {
        Text("stdout", fontWeight = FontWeight.SemiBold)
        Text(result.stdout, fontFamily = FontFamily.Monospace)
    }
    if (result.stderr.isNotBlank()) {
        Text("stderr", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
        Text(result.stderr, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun RuntimeCard(
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
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun RuntimeInfoRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value)
    }
}

private fun buildRuntimeDiagnosticReport(
    health: RuntimeHealthReport?,
    healthError: String?,
    shellResult: ShellCommandResult?,
    shellError: String?,
    workspacePath: String,
    fileStatus: String,
    auditPath: String,
): String = buildString {
    appendLine("AutoCrackApp Root Runtime Foundation 诊断")
    appendLine("版本：${BuildConfig.VERSION_NAME}")
    appendLine("工作区：$workspacePath")
    appendLine()
    appendLine("1. Runtime 健康状态")
    if (health == null) {
        appendLine("   失败：${healthError ?: "无结果"}")
    } else {
        appendLine("   模式：${health.capabilityMode}")
        appendLine("   Rootfs：${health.rootfsState}")
        appendLine("   Root：${health.rootIdentity ?: "未知"}")
        appendLine("   架构：${health.architecture ?: "未知"}")
        appendLine("   SELinux：${health.selinuxContext ?: "未知"}")
        appendLine("   可用命令：${health.availableCommands.joinToString()}")
        appendLine("   缺失命令：${health.missingCommands.joinToString()}")
        health.diagnostics.forEach { diagnostic -> appendLine("   - $diagnostic") }
    }
    appendLine()
    appendLine("2. 最近 Shell 命令")
    if (shellResult == null) {
        appendLine("   未完成：${shellError ?: "未执行"}")
    } else {
        appendLine("   请求 ID：${shellResult.requestId}")
        appendLine("   命令：${shellResult.command}")
        appendLine("   退出码：${shellResult.exitCode}")
        appendLine("   耗时：${shellResult.durationMillis} ms")
        appendLine("   超时：${shellResult.timedOut}")
        appendLine("   取消：${shellResult.cancelled}")
        appendLine("   failure：${shellResult.failure ?: "无"}")
        appendLine("   stdout：")
        appendLine(shellResult.stdout.take(MAX_DIAGNOSTIC_OUTPUT_CHARS))
        appendLine("   stderr：")
        appendLine(shellResult.stderr.take(MAX_DIAGNOSTIC_OUTPUT_CHARS))
    }
    appendLine()
    appendLine("3. 文件测试")
    appendLine("   $fileStatus")
    appendLine()
    appendLine("4. 审计")
    appendLine("   $auditPath")
}

private const val MAX_AUDIT_LINES = 12
private const val MAX_DIAGNOSTIC_OUTPUT_CHARS = 20_000
