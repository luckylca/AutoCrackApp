package com.luckylca.autocrack.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.luckylca.autocrack.runtime.DynamicHostReadBridge
import com.luckylca.autocrack.runtime.HostDebuggerAuthorization
import com.luckylca.autocrack.runtime.HostDebuggerSessionManager
import com.luckylca.autocrack.runtime.HostDebuggerSessionSnapshot
import com.luckylca.autocrack.runtime.HostProcessListReport
import kotlinx.coroutines.launch

@Composable
fun DebuggerSessionScreen(
    bridge: DynamicHostReadBridge,
    manager: HostDebuggerSessionManager,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var processFilter by remember { mutableStateOf("") }
    var packageName by remember { mutableStateOf("") }
    var pidText by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf(HostDebuggerSessionManager.DEFAULT_PORT.toString()) }
    var authorization by remember { mutableStateOf("") }
    var processReport by remember { mutableStateOf<HostProcessListReport?>(null) }
    var snapshot by remember { mutableStateOf(manager.snapshot()) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("尚未执行调试器操作") }

    fun expectedPhrase(): String? {
        val pid = pidText.toIntOrNull() ?: return null
        return runCatching { HostDebuggerAuthorization.expected(packageName.trim(), pid) }.getOrNull()
    }

    fun listProcesses() {
        scope.launch {
            loading = true
            status = "正在只读枚举候选目标"
            runCatching { bridge.listProcesses(processFilter.trim(), maxCount = 256) }
                .onSuccess { report ->
                    processReport = report
                    status = if (report.commandResult.succeeded) {
                        "候选目标枚举完成：${report.processes.size} 个结果"
                    } else {
                        "候选目标枚举失败：${report.commandResult.stderr.ifBlank { report.commandResult.failure ?: "未知错误" }}"
                    }
                }
                .onFailure { exception -> status = exception.message ?: "候选目标枚举失败" }
            loading = false
        }
    }

    fun startDebugger() {
        val pid = pidText.toIntOrNull()
        val port = portText.toIntOrNull()
        val targetPackage = packageName.trim()
        if (pid == null || pid <= 0 || port == null || targetPackage.isBlank()) {
            status = "需要有效包名、正整数 PID 和端口"
            return
        }
        scope.launch {
            loading = true
            status = "正在进行最终 PID 身份复核并显式 attach；目标会暂时停止"
            runCatching {
                manager.start(
                    packageName = targetPackage,
                    pid = pid,
                    port = port,
                    authorizationPhrase = authorization,
                )
            }.onSuccess { result ->
                snapshot = result
                status = if (result.attachedObserved) {
                    "LLDB server 已附加：TracerPid=${result.tracerPidCurrent}，目标当前处于调试停止状态"
                } else if (result.running) {
                    "LLDB server helper 已启动，但尚未确认 TracerPid；请刷新状态"
                } else {
                    "LLDB server 已退出：exit=${result.exitCode ?: "无"}"
                }
            }.onFailure { exception -> status = exception.message ?: "LLDB server attach 失败" }
            loading = false
        }
    }

    fun refreshDebugger() {
        scope.launch {
            loading = true
            runCatching { manager.refresh() }
                .onSuccess { result ->
                    snapshot = result
                    status = result?.let {
                        "Debugger 状态：running=${it.running}, tracer=${it.tracerPidCurrent ?: "未知"}, detachVerified=${it.detachVerified}"
                    } ?: "当前没有 debugger session"
                }
                .onFailure { exception -> status = exception.message ?: "刷新 debugger 状态失败" }
            loading = false
        }
    }

    fun detachDebugger() {
        scope.launch {
            loading = true
            status = "正在终止 AutoCrack LLDB helper 并验证 TracerPid 恢复为 0"
            runCatching { manager.stop() }
                .onSuccess { result ->
                    snapshot = result
                    status = result?.let {
                        if (it.detachVerified) {
                            "Detach 已验证：TracerPid=${it.tracerPidCurrent}；请确认目标应用恢复响应"
                        } else {
                            "LLDB helper 已结束，但尚未确认安全 detach；不要继续新的 attach"
                        }
                    } ?: "当前没有 debugger session"
                }
                .onFailure { exception -> status = exception.message ?: "安全 detach 失败" }
            loading = false
        }
    }

    fun copyDiagnostics() {
        val text = buildDebuggerDiagnostic(status, snapshot, manager.auditFile.path)
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
            ClipData.newPlainText("AutoCrackApp Debugger 诊断", text),
        )
        Toast.makeText(context, "Debugger 诊断已复制", Toast.LENGTH_SHORT).show()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 72.dp, start = 16.dp, end = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "Controlled LLDB Server",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "${BuildConfig.VERSION_NAME} · 显式授权 attach/detach · 仅 127.0.0.1",
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "警告：attach 会立即暂停目标进程。这一阶段只验证 server attach/detach，不连接 LLDB client，不发送断点、寄存器或内存命令。",
                color = MaterialTheme.colorScheme.error,
            )
        }

        item {
            DebuggerCard("1. 选择明确授权的目标") {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = processFilter,
                    onValueChange = { processFilter = it },
                    label = { Text("包名/进程过滤") },
                    singleLine = true,
                )
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = ::listProcesses,
                    enabled = !loading,
                ) {
                    Text("只读枚举候选进程")
                }
                processReport?.processes?.take(MAX_PROCESS_ROWS)?.forEach { process ->
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            pidText = process.pid.toString()
                            val argv0 = process.commandLine.trim().substringBefore(' ')
                            packageName = argv0.substringBefore(':')
                            authorization = ""
                        },
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
            }
        }

        item {
            DebuggerCard("2. 显式授权 attach") {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = packageName,
                    onValueChange = {
                        packageName = it
                        authorization = ""
                    },
                    label = { Text("目标包名") },
                    singleLine = true,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = pidText,
                    onValueChange = {
                        pidText = it.filter(Char::isDigit)
                        authorization = ""
                    },
                    label = { Text("目标 PID") },
                    singleLine = true,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = portText,
                    onValueChange = { portText = it.filter(Char::isDigit) },
                    label = { Text("Loopback 端口") },
                    singleLine = true,
                )
                expectedPhrase()?.let { phrase ->
                    Text("请输入以下完整授权短语：", fontWeight = FontWeight.SemiBold)
                    SelectionContainer {
                        Text(phrase, fontFamily = FontFamily.Monospace)
                    }
                }
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = authorization,
                    onValueChange = { authorization = it },
                    label = { Text("授权短语") },
                    singleLine = true,
                )
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = ::startDebugger,
                    enabled = !loading && snapshot?.running != true,
                ) {
                    Text("显式授权并启动 LLDB server attach")
                }
            }
        }

        item {
            DebuggerCard("3. 会话状态与安全 detach") {
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = ::refreshDebugger,
                    enabled = !loading,
                ) {
                    Text("刷新 TracerPid / helper 状态")
                }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = ::detachDebugger,
                    enabled = !loading && snapshot?.running == true,
                ) {
                    Text("安全终止 LLDB helper 并验证 detach")
                }
                snapshot?.let { result -> DebuggerSnapshot(result) }
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

        item {
            DebuggerCard("审计与诊断") {
                Text("Debugger 审计：${manager.auditFile.path}", fontFamily = FontFamily.Monospace)
                Text(
                    "审计会明确记录 attachAttempted/targetStateChanged/helperSignalSent；不会把 debugger attach 伪装成 read-only。",
                )
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = ::copyDiagnostics,
                ) {
                    Text("复制完整 Debugger 诊断")
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun DebuggerSnapshot(snapshot: HostDebuggerSessionSnapshot) {
    Text("Session=${snapshot.sessionId}", fontFamily = FontFamily.Monospace)
    Text("package=${snapshot.packageName} pid=${snapshot.pid} port=${snapshot.port}", fontFamily = FontFamily.Monospace)
    Text("running=${snapshot.running} helperPid=${snapshot.helperPid ?: "无"} exit=${snapshot.exitCode ?: "无"}", fontFamily = FontFamily.Monospace)
    Text("attachedObserved=${snapshot.attachedObserved} tracerBefore=${snapshot.tracerPidBefore} tracerCurrent=${snapshot.tracerPidCurrent ?: "未知"}", fontFamily = FontFamily.Monospace)
    Text("stateBefore=${snapshot.targetStateBefore ?: "未知"}", fontFamily = FontFamily.Monospace)
    Text("stateCurrent=${snapshot.targetStateCurrent ?: "未知"}", fontFamily = FontFamily.Monospace)
    Text("targetStateChanged=${snapshot.targetStateChanged} detachVerified=${snapshot.detachVerified}", fontFamily = FontFamily.Monospace)
    Text("targetSignalAttempted=${snapshot.targetSignalAttempted} helperSignalSent=${snapshot.helperSignalSent}", fontFamily = FontFamily.Monospace)
    Text("autoCrackClientConnected=${snapshot.autoCrackClientConnected} memoryCommandSent=${snapshot.memoryCommandSent}", fontFamily = FontFamily.Monospace)
    Text("registerWriteCommandSent=${snapshot.registerWriteCommandSent} breakpointCommandSent=${snapshot.breakpointCommandSent}", fontFamily = FontFamily.Monospace)
    snapshot.failure?.let { Text("failure=$it", color = MaterialTheme.colorScheme.error) }
    if (snapshot.stdout.isNotBlank()) {
        SelectionContainer {
            Text(snapshot.stdout.takeLast(MAX_OUTPUT_CHARS), fontFamily = FontFamily.Monospace)
        }
    }
    if (snapshot.stderr.isNotBlank()) {
        SelectionContainer {
            Text(
                snapshot.stderr.takeLast(MAX_OUTPUT_CHARS),
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun DebuggerCard(title: String, content: @Composable () -> Unit) {
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

private fun buildDebuggerDiagnostic(
    status: String,
    snapshot: HostDebuggerSessionSnapshot?,
    auditPath: String,
): String = buildString {
    appendLine("AutoCrackApp Controlled Debugger 诊断")
    appendLine("版本：${BuildConfig.VERSION_NAME}")
    appendLine("状态：$status")
    appendLine("审计：$auditPath")
    appendLine("边界：显式授权 attach/detach；loopback only；本阶段无 AutoCrack LLDB client、断点/寄存器/内存命令")
    snapshot?.let { result ->
        appendLine()
        appendLine("session=${result.sessionId}")
        appendLine("package=${result.packageName} pid=${result.pid} port=${result.port}")
        appendLine("running=${result.running} exit=${result.exitCode ?: "无"} failure=${result.failure ?: "无"}")
        appendLine("helperPid=${result.helperPid ?: "无"} helperSignalSent=${result.helperSignalSent}")
        appendLine("explicitAuthorizationVerified=${result.explicitAuthorizationVerified}")
        appendLine("attachAttempted=${result.attachAttempted} attachedObserved=${result.attachedObserved}")
        appendLine("tracerPidBefore=${result.tracerPidBefore} tracerPidCurrent=${result.tracerPidCurrent ?: "未知"}")
        appendLine("targetStateBefore=${result.targetStateBefore ?: "未知"}")
        appendLine("targetStateCurrent=${result.targetStateCurrent ?: "未知"}")
        appendLine("targetStateChanged=${result.targetStateChanged} detachVerified=${result.detachVerified}")
        appendLine("targetSignalAttempted=${result.targetSignalAttempted}")
        appendLine("autoCrackClientConnected=${result.autoCrackClientConnected}")
        appendLine("memoryCommandSent=${result.memoryCommandSent}")
        appendLine("registerWriteCommandSent=${result.registerWriteCommandSent}")
        appendLine("breakpointCommandSent=${result.breakpointCommandSent}")
        appendLine("outputTruncated=${result.outputTruncated}")
        if (result.stdout.isNotBlank()) appendLine("stdout:\n${result.stdout}")
        if (result.stderr.isNotBlank()) appendLine("stderr:\n${result.stderr}")
    }
}

private const val MAX_PROCESS_ROWS = 80
private const val MAX_OUTPUT_CHARS = 20_000
