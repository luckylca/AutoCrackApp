package com.luckylca.autocrack.ui

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.luckylca.autocrack.BuildConfig
import com.luckylca.autocrack.runtime.DynamicHostReadBridge
import com.luckylca.autocrack.runtime.HostDebuggerRecoveryAuthorization
import com.luckylca.autocrack.runtime.HostDebuggerRecoveryBridge
import com.luckylca.autocrack.runtime.HostDebuggerRecoverySnapshot
import com.luckylca.autocrack.runtime.HostProcessListReport
import kotlinx.coroutines.launch

@Composable
fun DebuggerRecoveryScreen(
    bridge: DynamicHostReadBridge,
    recoveryBridge: HostDebuggerRecoveryBridge,
) {
    val scope = rememberCoroutineScope()
    var processFilter by remember { mutableStateOf("") }
    var packageName by remember { mutableStateOf("") }
    var pidText by remember { mutableStateOf("") }
    var authorization by remember { mutableStateOf("") }
    var processReport by remember { mutableStateOf<HostProcessListReport?>(null) }
    var recovery by remember { mutableStateOf<HostDebuggerRecoverySnapshot?>(null) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("用于恢复 AutoCrackApp 重启/升级后遗留的 LLDB attach") }

    fun listProcesses() {
        scope.launch {
            loading = true
            runCatching { bridge.listProcesses(processFilter.trim(), maxCount = 256) }
                .onSuccess { report ->
                    processReport = report
                    status = "候选进程：${report.processes.size} 个"
                }
                .onFailure { exception -> status = exception.message ?: "进程枚举失败" }
            loading = false
        }
    }

    fun inspectOrphan() {
        val pid = pidText.toIntOrNull()
        val targetPackage = packageName.trim()
        if (pid == null || pid <= 0 || targetPackage.isBlank()) {
            status = "请输入有效包名和 PID"
            return
        }
        scope.launch {
            loading = true
            authorization = ""
            runCatching { recoveryBridge.inspect(targetPackage, pid) }
                .onSuccess { result ->
                    recovery = result
                    status = if (result.orphanVerified) {
                        "已验证遗留 AutoCrack LLDB helper：TracerPid=${result.tracerPid}"
                    } else {
                        "没有可安全恢复的 AutoCrack LLDB helper：${result.failure ?: "未知原因"}"
                    }
                }
                .onFailure { exception ->
                    recovery = null
                    status = exception.message ?: "遗留 attach 检查失败"
                }
            loading = false
        }
    }

    fun recoverDetach() {
        val current = recovery
        val tracerPid = current?.tracerPid
        if (current?.orphanVerified != true || tracerPid == null) {
            status = "请先完成遗留 helper 身份检查"
            return
        }
        scope.launch {
            loading = true
            status = "正在再次验证 target↔tracer↔LLDB helper 身份并安全 TERM helper"
            runCatching {
                recoveryBridge.recoverDetach(
                    packageName = current.packageName,
                    pid = current.pid,
                    tracerPid = tracerPid,
                    authorizationPhrase = authorization,
                )
            }.onSuccess { result ->
                recovery = result
                status = if (result.detachVerified) {
                    "恢复完成：目标 TracerPid 已回到 0；现在可返回“调试”页重新 attach"
                } else {
                    result.failure ?: "helper 已结束，但尚未验证 detach"
                }
            }.onFailure { exception -> status = exception.message ?: "恢复 detach 失败" }
            loading = false
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 72.dp, start = 16.dp, end = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Debugger Recovery", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("${BuildConfig.VERSION_NAME} · 只恢复身份已验证的 AutoCrack LLDB helper")
            Text(
                "不会根据一个裸 PID 直接 kill；目标 TracerPid、helper argv0、gdbserver --attach 参数和受信任二进制路径必须全部匹配。",
                color = MaterialTheme.colorScheme.error,
            )
        }

        item {
            RecoveryCard("1. 选择被遗留 tracer 挂住的目标") {
                OutlinedTextField(
                    value = processFilter,
                    onValueChange = { processFilter = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("包名/进程过滤") },
                    singleLine = true,
                )
                Button(
                    onClick = ::listProcesses,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !loading,
                ) { Text("只读枚举候选进程") }
                processReport?.processes?.take(MAX_PROCESS_ROWS)?.forEach { process ->
                    OutlinedButton(
                        onClick = {
                            pidText = process.pid.toString()
                            packageName = process.commandLine.trim().substringBefore(' ').substringBefore(':')
                            recovery = null
                            authorization = ""
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text("PID=${process.pid} ${process.name}", fontFamily = FontFamily.Monospace)
                            Text(process.commandLine.ifBlank { "<empty cmdline>" }, maxLines = 2)
                        }
                    }
                }
                OutlinedTextField(
                    value = packageName,
                    onValueChange = {
                        packageName = it
                        recovery = null
                        authorization = ""
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("目标包名") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = pidText,
                    onValueChange = {
                        pidText = it.filter(Char::isDigit)
                        recovery = null
                        authorization = ""
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("目标 PID") },
                    singleLine = true,
                )
                Button(
                    onClick = ::inspectOrphan,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !loading,
                ) { Text("检查是否为 AutoCrack 遗留 LLDB attach") }
            }
        }

        item {
            RecoveryCard("2. 精确授权并恢复 detach") {
                recovery?.let { result ->
                    Text("orphanVerified=${result.orphanVerified}", fontFamily = FontFamily.Monospace)
                    Text("target=${result.packageName}/${result.pid}", fontFamily = FontFamily.Monospace)
                    Text("tracerPid=${result.tracerPid ?: "无"}", fontFamily = FontFamily.Monospace)
                    Text("helperSignalSent=${result.helperSignalSent}", fontFamily = FontFamily.Monospace)
                    Text("targetSignalAttempted=${result.targetSignalAttempted}", fontFamily = FontFamily.Monospace)
                    Text("detachVerified=${result.detachVerified}", fontFamily = FontFamily.Monospace)
                    result.failure?.let { Text("failure=$it", color = MaterialTheme.colorScheme.error) }
                    if (result.orphanVerified && result.tracerPid != null && !result.detachVerified) {
                        val phrase = HostDebuggerRecoveryAuthorization.expected(
                            result.packageName,
                            result.pid,
                            result.tracerPid,
                        )
                        Text("输入以下完整授权短语：", fontWeight = FontWeight.SemiBold)
                        SelectionContainer { Text(phrase, fontFamily = FontFamily.Monospace) }
                    }
                }
                OutlinedTextField(
                    value = authorization,
                    onValueChange = { authorization = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("RECOVER 授权短语") },
                    singleLine = true,
                )
                Button(
                    onClick = ::recoverDetach,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !loading && recovery?.orphanVerified == true && recovery?.detachVerified != true,
                ) { Text("安全终止已验证的遗留 LLDB helper") }
            }
        }

        if (loading) {
            item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        }
        item { Text(status, fontFamily = FontFamily.Monospace) }
        item {
            RecoveryCard("审计") {
                Text("${recoveryBridge.auditFile.path}", fontFamily = FontFamily.Monospace)
                Text("所有恢复事件固定记录 targetSignalAttempted=false。")
            }
        }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
private fun RecoveryCard(title: String, content: @Composable () -> Unit) {
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

private const val MAX_PROCESS_ROWS = 64
