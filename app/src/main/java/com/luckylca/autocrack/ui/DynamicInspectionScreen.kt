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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.runtime.getValue
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
import com.luckylca.autocrack.root.CommandResult
import com.luckylca.autocrack.root.ProcessRootCommandRunner
import com.luckylca.autocrack.root.RootDetector
import com.luckylca.autocrack.runtime.DynamicHostReadBridge
import com.luckylca.autocrack.runtime.HostProcessInspectionReport
import com.luckylca.autocrack.runtime.HostProcessListReport
import com.luckylca.autocrack.runtime.RuntimeLayout
import kotlinx.coroutines.launch

@Composable
fun DynamicInspectionScreen() {
    val context = LocalContext.current
    val layout = remember(context) { RuntimeLayout(context.applicationContext).initialize() }
    val runner = remember { ProcessRootCommandRunner() }
    val rootDetector = remember(runner) { RootDetector(runner) }
    val bridge = remember(layout, rootDetector, runner) {
        DynamicHostReadBridge(layout, rootDetector, runner)
    }
    val scope = rememberCoroutineScope()

    var processFilter by remember { mutableStateOf("") }
    var pidText by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("尚未执行动态宿主检查") }
    var processReport by remember { mutableStateOf<HostProcessListReport?>(null) }
    var inspectionReport by remember { mutableStateOf<HostProcessInspectionReport?>(null) }

    fun listProcesses() {
        scope.launch {
            loading = true
            inspectionReport = null
            status = "正在通过 Root 只读枚举 /proc"
            runCatching { bridge.listProcesses(processFilter.trim()) }
                .onSuccess { report ->
                    processReport = report
                    status = if (report.commandResult.succeeded) {
                        "进程枚举完成：${report.processes.size} 个结果"
                    } else {
                        report.commandResult.failureOrOutput("进程枚举失败")
                    }
                }
                .onFailure { exception -> status = exception.message ?: "进程枚举失败" }
            loading = false
        }
    }

    fun inspectProcess() {
        val pid = pidText.toIntOrNull()
        if (pid == null || pid <= 0) {
            status = "请输入有效的正整数 PID"
            return
        }
        scope.launch {
            loading = true
            status = "正在读取 PID $pid 的身份、前置条件、maps、线程和 FD"
            runCatching { bridge.inspectProcess(pid) }
                .onSuccess { report ->
                    inspectionReport = report
                    status = if (report.succeeded) {
                        "PID $pid 只读检查完成：${report.loadedModules.size} 个映射文件"
                    } else {
                        "PID $pid 检查存在不可读项目；未尝试附加或修改目标"
                    }
                }
                .onFailure { exception -> status = exception.message ?: "PID 检查失败" }
            loading = false
        }
    }

    fun copyDiagnostics() {
        val text = buildDynamicDiagnosticReport(
            processReport = processReport,
            inspectionReport = inspectionReport,
            status = status,
            auditPath = bridge.auditFile.path,
        )
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
            ClipData.newPlainText("AutoCrackApp Dynamic Host 诊断", text),
        )
        Toast.makeText(context, "动态检查诊断已复制", Toast.LENGTH_SHORT).show()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 72.dp, start = 16.dp, end = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "Dynamic Host Foundation",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "${BuildConfig.VERSION_NAME} · 只读 /proc · 不附加、不注入、不写内存",
                color = MaterialTheme.colorScheme.primary,
            )
        }

        item {
            DynamicCard("进程发现") {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = processFilter,
                    onValueChange = { processFilter = it },
                    label = { Text("包名、进程名或命令行过滤（可空）") },
                    singleLine = true,
                )
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = ::listProcesses,
                    enabled = !loading,
                ) {
                    Text("只读枚举宿主进程")
                }
                processReport?.let { report ->
                    DynamicInfoRow("命令退出码", report.commandResult.exitCode?.toString() ?: "无")
                    DynamicInfoRow("结果数量", report.processes.size.toString())
                    report.processes.take(MAX_PROCESS_ROWS).forEach { process ->
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { pidText = process.pid.toString() },
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    "PID=${process.pid} UID=${process.uid ?: -1} ${process.name}",
                                    fontFamily = FontFamily.Monospace,
                                )
                                Text(
                                    process.commandLine.ifBlank { "<empty cmdline>" },
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                )
                            }
                        }
                    }
                    if (report.processes.size > MAX_PROCESS_ROWS) {
                        Text("界面仅显示前 $MAX_PROCESS_ROWS 个结果；复制诊断保留完整计数")
                    }
                }
            }
        }

        item {
            DynamicCard("PID 只读检查") {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = pidText,
                    onValueChange = { pidText = it.filter(Char::isDigit) },
                    label = { Text("目标 PID") },
                    singleLine = true,
                )
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = ::inspectProcess,
                    enabled = !loading && pidText.isNotBlank(),
                ) {
                    Text("读取身份、Maps、线程、FD 和附加前置条件")
                }
                Text(
                    "此按钮不会调用 ptrace、gdbserver、lldb-server、kill、写文件或写内存。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (loading) {
            item {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(status)
            }
        } else {
            item { Text(status, fontFamily = FontFamily.Monospace) }
        }

        inspectionReport?.let { report ->
            item {
                DynamicCard("目标身份与附加前置检查") {
                    DynamicCommandResult("身份快照", report.identity)
                    DynamicCommandResult("附加前置检查", report.attachPreflight)
                }
            }
            item {
                DynamicCard("已加载映射文件") {
                    DynamicInfoRow("映射文件数量", report.loadedModules.size.toString())
                    report.loadedModules.take(MAX_MODULE_ROWS).forEach { module ->
                        Text(
                            buildString {
                                append(if (module.executable) "[X] " else "[-] ")
                                append("0x${module.firstAddress.toString(16)} ")
                                append(module.path)
                                append(" · segments=${module.segmentCount}")
                            },
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            item {
                DynamicCard("线程与文件描述符") {
                    DynamicCommandResult("线程", report.threads)
                    DynamicCommandResult("文件描述符", report.fileDescriptors)
                }
            }
            item {
                DynamicCard("原始 Maps") {
                    DynamicCommandResult("/proc/${report.pid}/maps", report.maps)
                }
            }
        }

        item {
            DynamicCard("审计与诊断") {
                DynamicInfoRow("审计文件", bridge.auditFile.path)
                Text("每条记录都标记 readOnly=true、stateChanged=false、attachAttempted=false。")
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = ::copyDiagnostics,
                ) {
                    Text("复制完整动态检查诊断")
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun DynamicCommandResult(title: String, result: CommandResult) {
    Text(title, fontWeight = FontWeight.SemiBold)
    DynamicInfoRow("退出码", result.exitCode?.toString() ?: "无")
    if (result.failure != null || result.timedOut || result.exitCode != 0) {
        Text(result.failureOrOutput("命令失败"), color = MaterialTheme.colorScheme.error)
    }
    if (result.stdout.isNotBlank()) {
        SelectionContainer {
            Text(
                result.stdout.take(MAX_VISIBLE_OUTPUT_CHARS),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (result.stdout.length > MAX_VISIBLE_OUTPUT_CHARS) {
            Text("界面输出已限制为 $MAX_VISIBLE_OUTPUT_CHARS 字符；底层命令结果和审计仍保留计数。")
        }
    }
    if (result.stderr.isNotBlank()) {
        Text(
            result.stderr.take(MAX_VISIBLE_OUTPUT_CHARS),
            color = MaterialTheme.colorScheme.error,
            fontFamily = FontFamily.Monospace,
        )
    }
    HorizontalDivider()
}

@Composable
private fun DynamicCard(title: String, content: @Composable () -> Unit) {
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
private fun DynamicInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontFamily = FontFamily.Monospace)
    }
}

private fun CommandResult.failureOrOutput(fallback: String): String = when {
    timedOut -> "命令超时"
    failure != null -> failure
    stderr.isNotBlank() -> stderr
    stdout.isNotBlank() -> stdout
    else -> fallback
}

private fun buildDynamicDiagnosticReport(
    processReport: HostProcessListReport?,
    inspectionReport: HostProcessInspectionReport?,
    status: String,
    auditPath: String,
): String = buildString {
    appendLine("AutoCrackApp Dynamic Host 诊断")
    appendLine("版本：${BuildConfig.VERSION_NAME}")
    appendLine("状态：$status")
    appendLine("审计：$auditPath")
    appendLine("安全边界：read-only /proc；未执行 attach、inject、signal 或 memory write")
    processReport?.let { report ->
        appendLine()
        appendLine("进程枚举：exit=${report.commandResult.exitCode} count=${report.processes.size}")
        appendLine("过滤：${report.filter.ifBlank { "无" }}")
    }
    inspectionReport?.let { report ->
        appendLine()
        appendLine("目标 PID：${report.pid}")
        appendLine("总体成功：${report.succeeded}")
        appendLine("模块数量：${report.loadedModules.size}")
        appendResult("identity", report.identity)
        appendResult("preflight", report.attachPreflight)
        appendResult("maps", report.maps)
        appendResult("threads", report.threads)
        appendResult("fds", report.fileDescriptors)
        appendLine("模块：")
        report.loadedModules.forEach { module ->
            appendLine(
                "  ${if (module.executable) "X" else "-"} " +
                    "0x${module.firstAddress.toString(16)} ${module.path}",
            )
        }
    }
}

private fun StringBuilder.appendResult(name: String, result: CommandResult) {
    appendLine()
    appendLine("[$name] exit=${result.exitCode} timeout=${result.timedOut} failure=${result.failure ?: "无"}")
    if (result.stdout.isNotBlank()) appendLine(result.stdout)
    if (result.stderr.isNotBlank()) appendLine("stderr:\n${result.stderr}")
}

private const val MAX_PROCESS_ROWS = 100
private const val MAX_MODULE_ROWS = 150
private const val MAX_VISIBLE_OUTPUT_CHARS = 20_000
