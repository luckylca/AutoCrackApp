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
import com.luckylca.autocrack.runtime.HostDebuggerControlAuthorization
import com.luckylca.autocrack.runtime.HostDebuggerControlBridge
import com.luckylca.autocrack.runtime.HostDebuggerControlGate
import com.luckylca.autocrack.runtime.HostDebuggerControlSnapshot
import com.luckylca.autocrack.runtime.HostDebuggerSessionManager
import com.luckylca.autocrack.runtime.HostDebuggerSessionSnapshot
import com.luckylca.autocrack.runtime.HostProcessListReport
import kotlinx.coroutines.launch

@Composable
fun DebuggerSessionScreen(
    bridge: DynamicHostReadBridge,
    manager: HostDebuggerSessionManager,
    controlBridge: HostDebuggerControlBridge,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var processFilter by remember { mutableStateOf("") }
    var packageName by remember { mutableStateOf("") }
    var pidText by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf(HostDebuggerSessionManager.DEFAULT_PORT.toString()) }
    var attachAuthorization by remember { mutableStateOf("") }
    var controlAuthorization by remember { mutableStateOf("") }
    var registerLimitText by remember { mutableStateOf("16") }
    var memoryAddressText by remember { mutableStateOf("") }
    var memoryLengthText by remember { mutableStateOf("32") }
    var processReport by remember { mutableStateOf<HostProcessListReport?>(null) }
    var serverSnapshot by remember { mutableStateOf(manager.snapshot()) }
    var controlSnapshot by remember { mutableStateOf(controlBridge.snapshot()) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("尚未执行调试器操作") }

    fun expectedAttachPhrase(): String? {
        val pid = pidText.toIntOrNull() ?: return null
        return runCatching { HostDebuggerAuthorization.expected(packageName.trim(), pid) }.getOrNull()
    }

    fun expectedControlPhrase(): String? {
        val server = serverSnapshot ?: return null
        return runCatching {
            HostDebuggerControlAuthorization.expected(server.packageName, server.pid)
        }.getOrNull()
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
            status = "正在最终复核 PID 身份并启动 LLDB server；目标可能在 client 握手后才进入 traced 状态"
            runCatching {
                manager.start(targetPackage, pid, port, attachAuthorization)
            }.onSuccess { result ->
                serverSnapshot = result
                controlSnapshot = controlBridge.snapshot()
                controlAuthorization = ""
                status = if (result.attachedObserved) {
                    "LLDB server 已附加：TracerPid=${result.tracerPidCurrent}；可进行第二层 CONTROL 授权"
                } else if (result.running && result.tracerPidCurrent == 0) {
                    "LLDB server helper 已启动且 TracerPid=0；可输入第二层 CONTROL，让 client 握手完成并确认 attach"
                } else if (result.running) {
                    "LLDB server helper 已启动，但当前 attach 状态未确认；请刷新或查看诊断"
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
                    serverSnapshot = result
                    controlSnapshot = controlBridge.snapshot()
                    status = result?.let {
                        "Debugger：serverRunning=${it.running}, tracer=${it.tracerPidCurrent ?: "未知"}, clientConnected=${controlSnapshot.connected}"
                    } ?: "当前没有 debugger session"
                }
                .onFailure { exception -> status = exception.message ?: "刷新 debugger 状态失败" }
            loading = false
        }
    }

    fun connectClient() {
        scope.launch {
            loading = true
            status = "正在连接 127.0.0.1 LLDB gdb-remote；连接后必须再次确认可信 TracerPid，不会发送写入或断点命令"
            runCatching { controlBridge.connect(controlAuthorization) }
                .onSuccess { result ->
                    controlSnapshot = result
                    status = "LLDB client 已连接且 attach 已确认：stop=${result.lastStopReply ?: "未知"} capabilities=${result.capabilities.size}"
                }
                .onFailure { exception -> status = exception.message ?: "LLDB client 连接失败" }
            serverSnapshot = runCatching { manager.refresh() }.getOrNull() ?: serverSnapshot
            loading = false
        }
    }

    fun readRegisters() {
        val limit = registerLimitText.toIntOrNull()
        if (limit == null) {
            status = "寄存器数量必须是整数"
            return
        }
        scope.launch {
            loading = true
            status = "正在读取寄存器；不会写寄存器"
            runCatching { controlBridge.readRegisters(limit) }
                .onSuccess { result ->
                    controlSnapshot = result
                    status = "寄存器读取完成：${result.registers.size} 个"
                }
                .onFailure { exception -> status = exception.message ?: "寄存器读取失败" }
            loading = false
        }
    }

    fun readMemory() {
        val address = parseHexAddress(memoryAddressText)
        val length = memoryLengthText.toIntOrNull()
        if (address == null || length == null) {
            status = "请输入有效十六进制地址和读取长度"
            return
        }
        scope.launch {
            loading = true
            status = "正在执行限长内存读取；不会写入目标内存"
            runCatching { controlBridge.readMemory(address, length) }
                .onSuccess { result ->
                    controlSnapshot = result
                    status = "内存读取完成：0x${address.toString(16)}，${result.lastMemoryHex?.length?.div(2) ?: 0} B"
                }
                .onFailure { exception -> status = exception.message ?: "内存读取失败" }
            loading = false
        }
    }

    fun stepTarget() {
        scope.launch {
            loading = true
            status = "正在单步执行 1 条指令并等待再次停止"
            runCatching { controlBridge.step() }
                .onSuccess { result ->
                    controlSnapshot = result
                    status = "单步完成：stop=${result.lastStopReply ?: "未知"}"
                }
                .onFailure { exception -> status = exception.message ?: "单步失败" }
            loading = false
        }
    }

    fun continueTarget() {
        scope.launch {
            loading = true
            status = "正在恢复目标运行；之后可用协议 interrupt 让目标再次停止"
            runCatching { controlBridge.continueTarget() }
                .onSuccess { result ->
                    controlSnapshot = result
                    status = "continue 已发送；targetRunning=${result.targetRunning}"
                }
                .onFailure { exception -> status = exception.message ?: "continue 失败" }
            loading = false
        }
    }

    fun interruptTarget() {
        scope.launch {
            loading = true
            status = "正在发送 gdb-remote interrupt；这不是 kill(targetPid)"
            runCatching { controlBridge.interrupt() }
                .onSuccess { result ->
                    controlSnapshot = result
                    status = "interrupt 已发送：targetRunning=${result.targetRunning}, stop=${result.lastStopReply ?: "等待中"}"
                }
                .onFailure { exception -> status = exception.message ?: "interrupt 失败" }
            loading = false
        }
    }

    fun detachDebugger() {
        scope.launch {
            loading = true
            status = "正在关闭 loopback client、终止受信任 LLDB helper 并验证 TracerPid=0"
            runCatching {
                controlSnapshot = controlBridge.prepareForDetach()
                manager.stop()
            }.onSuccess { result ->
                serverSnapshot = result
                controlSnapshot = controlBridge.snapshot()
                status = result?.let {
                    if (it.detachVerified) {
                        "Detach 已验证：TracerPid=${it.tracerPidCurrent}；请确认目标恢复响应"
                    } else {
                        "helper 已结束但尚未验证 detach；不要开始新的 attach"
                    }
                } ?: "当前没有 debugger session"
            }.onFailure { exception -> status = exception.message ?: "安全 detach 失败" }
            loading = false
        }
    }

    fun copyDiagnostics() {
        val text = buildDebuggerDiagnostic(
            status,
            serverSnapshot,
            controlSnapshot,
            manager.auditFile.path,
            controlBridge.auditFile.path,
        )
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
                "Controlled LLDB Debugger",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "${BuildConfig.VERSION_NAME} · server attach + bounded loopback client",
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "边界：可读取寄存器/限长内存并执行 continue/step/interrupt；没有寄存器写、内存写、断点或任意 raw packet 接口。",
                color = MaterialTheme.colorScheme.error,
            )
        }
        item {
            DebuggerCard("1. 选择明确授权的目标") {
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
                            attachAuthorization = ""
                            controlAuthorization = ""
                        },
                        modifier = Modifier.fillMaxWidth(),
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
            DebuggerCard("2. 第一层授权：attach LLDB server") {
                OutlinedTextField(
                    value = packageName,
                    onValueChange = {
                        packageName = it
                        attachAuthorization = ""
                        controlAuthorization = ""
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("目标包名") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = pidText,
                    onValueChange = {
                        pidText = it.filter(Char::isDigit)
                        attachAuthorization = ""
                        controlAuthorization = ""
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("目标 PID") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter(Char::isDigit) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Loopback 端口") },
                    singleLine = true,
                )
                expectedAttachPhrase()?.let { phrase ->
                    Text("Attach 授权短语：", fontWeight = FontWeight.SemiBold)
                    SelectionContainer { Text(phrase, fontFamily = FontFamily.Monospace) }
                }
                OutlinedTextField(
                    value = attachAuthorization,
                    onValueChange = { attachAuthorization = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("ATTACH 授权短语") },
                    singleLine = true,
                )
                Button(
                    onClick = ::startDebugger,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !loading && serverSnapshot?.running != true,
                ) { Text("显式授权并启动 LLDB server attach") }
            }
        }
        item {
            DebuggerCard("3. 第二层授权：连接 LLDB client") {
                expectedControlPhrase()?.let { phrase ->
                    Text("执行控制会恢复目标运行，请再次输入：", fontWeight = FontWeight.SemiBold)
                    SelectionContainer { Text(phrase, fontFamily = FontFamily.Monospace) }
                }
                val pendingClientAttach = serverSnapshot?.let { server ->
                    server.running && !server.attachedObserved && server.tracerPidCurrent == 0 && server.failure == null
                } == true
                if (pendingClientAttach) {
                    Text(
                        "当前 LLDB server 正在运行但 TracerPid 仍为 0；此设备可能需要 client 握手后才完成 attach。完成 CONTROL 授权后可直接连接，连接后会再次强制验证 TracerPid。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedTextField(
                    value = controlAuthorization,
                    onValueChange = { controlAuthorization = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("CONTROL 授权短语") },
                    singleLine = true,
                )
                Button(
                    onClick = ::connectClient,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !loading &&
                        serverSnapshot?.let(HostDebuggerControlGate::canAttemptConnection) == true &&
                        !controlSnapshot.connected,
                ) { Text("授权并连接 127.0.0.1 LLDB client") }
                ControlSnapshot(controlSnapshot)
            }
        }
        item {
            DebuggerCard("4. 只读观察") {
                OutlinedTextField(
                    value = registerLimitText,
                    onValueChange = { registerLimitText = it.filter(Char::isDigit) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("寄存器数量（1..128）") },
                    singleLine = true,
                )
                OutlinedButton(
                    onClick = ::readRegisters,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !loading && controlSnapshot.connected && !controlSnapshot.targetRunning,
                ) { Text("读取寄存器（只读）") }
                controlSnapshot.registers.take(MAX_REGISTER_ROWS).forEach { register ->
                    Text(
                        "#${register.index} ${register.name} ${register.bitSize ?: "?"}bit = ${register.rawHex}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedTextField(
                    value = memoryAddressText,
                    onValueChange = { memoryAddressText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("内存地址（hex，例如 0x7abc...）") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = memoryLengthText,
                    onValueChange = { memoryLengthText = it.filter(Char::isDigit) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("读取字节数（1..512）") },
                    singleLine = true,
                )
                OutlinedButton(
                    onClick = ::readMemory,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !loading && controlSnapshot.connected && !controlSnapshot.targetRunning,
                ) { Text("读取内存（只读、限长）") }
                controlSnapshot.lastMemoryHex?.let { hex ->
                    SelectionContainer {
                        Text(
                            "0x${controlSnapshot.lastMemoryAddress?.toString(16)}: $hex",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        item {
            DebuggerCard("5. 执行控制") {
                Text("continue/step 会改变目标执行状态，但不会修改寄存器值、内存内容或插入断点。")
                Button(
                    onClick = ::stepTarget,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !loading && controlSnapshot.connected && !controlSnapshot.targetRunning,
                ) { Text("Single step 1 instruction") }
                Button(
                    onClick = ::continueTarget,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !loading && controlSnapshot.connected && !controlSnapshot.targetRunning,
                ) { Text("Continue target") }
                OutlinedButton(
                    onClick = ::interruptTarget,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !loading && controlSnapshot.connected && controlSnapshot.targetRunning,
                ) { Text("Interrupt and stop again") }
            }
        }
        item {
            DebuggerCard("6. 状态与安全 detach") {
                OutlinedButton(
                    onClick = ::refreshDebugger,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !loading,
                ) { Text("刷新 TracerPid / client 状态") }
                Button(
                    onClick = ::detachDebugger,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !loading && serverSnapshot?.running == true,
                ) { Text("安全关闭 client/helper 并验证 detach") }
                serverSnapshot?.let { DebuggerSnapshot(it) }
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
                Text("Server 审计：${manager.auditFile.path}", fontFamily = FontFamily.Monospace)
                Text("Control 审计：${controlBridge.auditFile.path}", fontFamily = FontFamily.Monospace)
                Text("control 审计固定记录 registerWrite=false / memoryWrite=false / breakpoint=false / rawPacket=false。")
                Button(
                    onClick = ::copyDiagnostics,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("复制完整 Debugger 诊断") }
            }
        }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
private fun ControlSnapshot(snapshot: HostDebuggerControlSnapshot) {
    Text(
        "clientConnected=${snapshot.connected} controlAuthorized=${snapshot.controlAuthorizationVerified} targetRunning=${snapshot.targetRunning}",
        fontFamily = FontFamily.Monospace,
    )
    Text("lastStop=${snapshot.lastStopReply ?: "无"}", fontFamily = FontFamily.Monospace)
    Text(
        "continue=${snapshot.continueCommandSent} step=${snapshot.stepCommandSent} interrupt=${snapshot.interruptCommandSent}",
        fontFamily = FontFamily.Monospace,
    )
    Text(
        "registerRead=${snapshot.registerReadCommandSent} memoryRead=${snapshot.memoryReadCommandSent}",
        fontFamily = FontFamily.Monospace,
    )
    Text(
        "registerWrite=${snapshot.registerWriteCommandSent} memoryWrite=${snapshot.memoryWriteCommandSent} breakpoint=${snapshot.breakpointCommandSent}",
        fontFamily = FontFamily.Monospace,
    )
    snapshot.failure?.let { Text("controlFailure=$it", color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun DebuggerSnapshot(snapshot: HostDebuggerSessionSnapshot) {
    Text("Session=${snapshot.sessionId}", fontFamily = FontFamily.Monospace)
    Text("package=${snapshot.packageName} pid=${snapshot.pid} port=${snapshot.port}", fontFamily = FontFamily.Monospace)
    Text(
        "serverRunning=${snapshot.running} helperPid=${snapshot.helperPid ?: "无"} exit=${snapshot.exitCode ?: "无"}",
        fontFamily = FontFamily.Monospace,
    )
    Text(
        "attachedObserved=${snapshot.attachedObserved} tracerBefore=${snapshot.tracerPidBefore} tracerCurrent=${snapshot.tracerPidCurrent ?: "未知"}",
        fontFamily = FontFamily.Monospace,
    )
    Text(
        "targetStateChanged=${snapshot.targetStateChanged} detachVerified=${snapshot.detachVerified}",
        fontFamily = FontFamily.Monospace,
    )
    Text(
        "targetSignalAttempted=${snapshot.targetSignalAttempted} helperSignalSent=${snapshot.helperSignalSent}",
        fontFamily = FontFamily.Monospace,
    )
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

private fun parseHexAddress(text: String): Long? {
    val normalized = text.trim().removePrefix("0x").removePrefix("0X")
    if (normalized.isBlank()) return null
    return normalized.toLongOrNull(16)?.takeIf { it >= 0L }
}

private fun buildDebuggerDiagnostic(
    status: String,
    server: HostDebuggerSessionSnapshot?,
    control: HostDebuggerControlSnapshot,
    serverAuditPath: String,
    controlAuditPath: String,
): String = buildString {
    appendLine("AutoCrackApp Controlled Debugger 诊断")
    appendLine("版本：${BuildConfig.VERSION_NAME}")
    appendLine("状态：$status")
    appendLine("Server审计：$serverAuditPath")
    appendLine("Control审计：$controlAuditPath")
    appendLine("边界：loopback only；寄存器/内存只读；允许显式授权 continue/step/interrupt；无 register write / memory write / breakpoint / raw packet adapter")
    server?.let { result ->
        appendLine()
        appendLine("[server] session=${result.sessionId}")
        appendLine("package=${result.packageName} pid=${result.pid} port=${result.port}")
        appendLine("running=${result.running} exit=${result.exitCode ?: "无"} failure=${result.failure ?: "无"}")
        appendLine("helperPid=${result.helperPid ?: "无"} helperSignalSent=${result.helperSignalSent}")
        appendLine("explicitAuthorizationVerified=${result.explicitAuthorizationVerified}")
        appendLine("attachAttempted=${result.attachAttempted} attachedObserved=${result.attachedObserved}")
        appendLine("tracerPidBefore=${result.tracerPidBefore} tracerPidCurrent=${result.tracerPidCurrent ?: "未知"}")
        appendLine("targetStateChanged=${result.targetStateChanged} detachVerified=${result.detachVerified}")
        appendLine("targetSignalAttempted=${result.targetSignalAttempted}")
    }
    appendLine()
    appendLine("[client-control]")
    appendLine("session=${control.sessionId ?: "无"} package=${control.packageName ?: "无"} pid=${control.pid ?: "无"} port=${control.port ?: "无"}")
    appendLine("controlAuthorizationVerified=${control.controlAuthorizationVerified}")
    appendLine("clientConnected=${control.connected} targetRunning=${control.targetRunning}")
    appendLine("lastStopReply=${control.lastStopReply ?: "无"}")
    appendLine("capabilityCount=${control.capabilities.size}")
    appendLine("continueCommandSent=${control.continueCommandSent}")
    appendLine("stepCommandSent=${control.stepCommandSent}")
    appendLine("interruptCommandSent=${control.interruptCommandSent}")
    appendLine("registerReadCommandSent=${control.registerReadCommandSent} registerCount=${control.registers.size}")
    appendLine("memoryReadCommandSent=${control.memoryReadCommandSent} memoryAddress=${control.lastMemoryAddress?.let { "0x${it.toString(16)}" } ?: "无"}")
    appendLine("registerWriteCommandSent=${control.registerWriteCommandSent}")
    appendLine("memoryWriteCommandSent=${control.memoryWriteCommandSent}")
    appendLine("breakpointCommandSent=${control.breakpointCommandSent}")
    appendLine("rawPacketAdapterExposed=false")
    appendLine("controlFailure=${control.failure ?: "无"}")
    if (control.registers.isNotEmpty()) {
        appendLine("registers:")
        control.registers.forEach {
            appendLine("  #${it.index} ${it.name} ${it.bitSize ?: "?"}bit=${it.rawHex}")
        }
    }
    control.lastMemoryHex?.let { appendLine("memoryHex=$it") }
}

private const val MAX_PROCESS_ROWS = 24
private const val MAX_REGISTER_ROWS = 48
private const val MAX_OUTPUT_CHARS = 8_000
