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
    var breakpointAddressText by remember { mutableStateOf("") }
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
            status = "正在复核 PID 身份并启动 targetless LLDB server；此步骤不会 attach 目标"
            runCatching { manager.start(targetPackage, pid, port, attachAuthorization) }
                .onSuccess { result ->
                    serverSnapshot = result
                    controlSnapshot = controlBridge.snapshot()
                    controlAuthorization = ""
                    status = when {
                        result.serverReadyForClient ->
                            "LLDB server 已在 127.0.0.1:${result.port} LISTEN；完成 CONTROL 后才发送 typed vAttach"
                        result.running ->
                            "LLDB helper 已启动但监听尚未就绪；请刷新并查看 helperVerified/serverReady"
                        else -> "LLDB server 已退出：exit=${result.exitCode ?: "无"}"
                    }
                }
                .onFailure { exception -> status = exception.message ?: "LLDB server 启动失败" }
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
                        "Debugger：serverRunning=${it.running}, helperVerified=${it.helperVerified}, ready=${it.serverReadyForClient}, tracer=${it.tracerPidCurrent ?: "未知"}, clientConnected=${controlSnapshot.connected}"
                    } ?: "当前没有 debugger session"
                }
                .onFailure { exception -> status = exception.message ?: "刷新 debugger 状态失败" }
            loading = false
        }
    }

    fun connectClient() {
        scope.launch {
            loading = true
            status = "正在连接 127.0.0.1 targetless LLDB server，复核目标后发送固定 typed vAttach；连接阶段不会发送断点、写入或 raw packet"
            runCatching { controlBridge.connect(controlAuthorization) }
                .onSuccess { result ->
                    controlSnapshot = result
                    status = "LLDB client 已连接且 typed attach 已确认：stop=${result.lastStopReply ?: "未知"} capabilities=${result.capabilities.size}"
                }
                .onFailure { exception ->
                    controlSnapshot = controlBridge.snapshot()
                    status = exception.message ?: "LLDB client / typed attach 失败"
                }
            serverSnapshot = runCatching { manager.refresh() }.getOrNull() ?: serverSnapshot
            controlSnapshot = controlBridge.snapshot()
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

    fun autoRunToSteppablePosition() {
        scope.launch {
            loading = true
            status = "正在自动选择主线程并读取 PC；随后设置一次性硬件执行锚点"
            runCatching {
                val prepared = controlBridge.autoPrepareSteppableAnchor()
                controlSnapshot = prepared
                val running = controlBridge.continueTarget()
                controlSnapshot = running
                running
            }.onSuccess { result ->
                status =
                    "自动观察已启动：thread=${result.autoAnchorThreadId ?: "未知"}，PC=${result.autoAnchorAddress?.let { "0x${it.toString(16)}" } ?: "未知"}。一次性 anchor 命中后会自动抓上下文并继续运行，不会把目标 App 长期停住；操作目标 App 后回到这里点“暂停运行并抓取当前上下文”。"
            }.onFailure { exception ->
                controlSnapshot = controlBridge.snapshot()
                status = exception.message ?: "自动准备可单步位置失败"
            }
            loading = false
        }
    }

    fun setHardwareBreakpoint() {
        val address = parseHexAddress(breakpointAddressText)
        if (address == null) {
            status = "请输入有效的 AArch64 指令地址（hex）"
            return
        }
        scope.launch {
            loading = true
            status = "正在设置 typed AArch64 硬件执行断点：0x${address.toString(16)}"
            runCatching { controlBridge.setHardwareExecutionBreakpoint(address) }
                .onSuccess { result ->
                    controlSnapshot = result
                    status = "硬件执行断点已设置：0x${address.toString(16)}；现在可以 Continue 等待命中"
                }
                .onFailure { exception -> status = exception.message ?: "硬件执行断点设置失败" }
            loading = false
        }
    }

    fun removeHardwareBreakpoint(address: Long) {
        scope.launch {
            loading = true
            status = "正在移除当前会话跟踪的硬件执行断点：0x${address.toString(16)}"
            runCatching { controlBridge.removeHardwareExecutionBreakpoint(address) }
                .onSuccess { result ->
                    controlSnapshot = result
                    status = "硬件执行断点已移除：0x${address.toString(16)}"
                }
                .onFailure { exception -> status = exception.message ?: "硬件执行断点移除失败" }
            loading = false
        }
    }

    fun refreshThreads() {
        scope.launch {
            loading = true
            status = "正在通过 typed qfThreadInfo/qThreadExtraInfo 只读枚举线程"
            runCatching { controlBridge.refreshThreads() }
                .onSuccess { result ->
                    controlSnapshot = result
                    status =
                        "线程枚举完成：${result.threads.size} 个；当前选择=${result.selectedThreadId ?: "无"}"
                }
                .onFailure { exception -> status = exception.message ?: "线程枚举失败" }
            loading = false
        }
    }

    fun selectThread(threadId: String) {
        scope.launch {
            loading = true
            status = "正在选择线程 $threadId 作为寄存器读取/单步目标"
            runCatching { controlBridge.selectThread(threadId) }
                .onSuccess { result ->
                    controlSnapshot = result
                    status = "已选择线程 ${result.selectedThreadId ?: "无"}"
                }
                .onFailure { exception -> status = exception.message ?: "线程选择失败" }
            loading = false
        }
    }

    fun stepTarget() {
        scope.launch {
            loading = true
            val selected = controlSnapshot.selectedThreadId
            status = "正在对所选线程 ${selected ?: "未选择"} 单步 1 条指令；阻塞 syscall 超时会自动 interrupt 恢复"
            runCatching { controlBridge.step() }
                .onSuccess { result ->
                    controlSnapshot = result
                    status = when {
                        result.stepCompleted ->
                            "单步完成：thread=${result.lastStepThreadId ?: "未知"} stop=${result.lastStopReply ?: "未知"}"
                        result.stepAutoInterruptRecovered ->
                            "线程 ${result.lastStepThreadId ?: "未知"} 在 2 秒内没有完成一条指令（常见于阻塞 syscall）；已自动 interrupt 恢复停止状态。注意：恢复成功，但这次 step 没有完成。"
                        else -> "step 未完成：stop=${result.lastStopReply ?: "未知"}"
                    }
                }
                .onFailure { exception ->
                    controlSnapshot = controlBridge.snapshot()
                    status = exception.message ?: "单步失败"
                }
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
        // Always copy the bridge's live state rather than the last Compose-rendered snapshot.
        // This matters while a blocking step/read operation is still in flight: the bridge has
        // already marked stepCommandSent/targetRunning, while the UI snapshot is only refreshed
        // when that suspend call returns.
        val liveServer = manager.snapshot() ?: serverSnapshot
        val liveControl = controlBridge.snapshot()
        serverSnapshot = liveServer
        controlSnapshot = liveControl
        val text = buildDebuggerDiagnostic(
            status,
            liveServer,
            liveControl,
            manager.auditFile.path,
            controlBridge.auditFile.path,
        )
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
            ClipData.newPlainText("AutoCrackApp Debugger 诊断", text),
        )
        Toast.makeText(context, "Debugger 诊断已复制（实时状态）", Toast.LENGTH_SHORT).show()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(top = 72.dp, start = 16.dp, end = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Controlled LLDB Debugger", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "${BuildConfig.VERSION_NAME} · targetless server + typed vAttach client",
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "边界：寄存器/内存只读；允许 typed AArch64 硬件执行断点与 continue/step/interrupt；无软件断点、寄存器写、内存写或任意 raw packet。",
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
                Button(onClick = ::listProcesses, modifier = Modifier.fillMaxWidth(), enabled = !loading) {
                    Text("只读枚举候选进程")
                }
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
            DebuggerCard("2. 第一层授权：准备 targetless LLDB server") {
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
                Text(
                    "这一层只固定授权目标并启动本地 gdbserver，不会 ptrace attach；实际 attach 发生在下一层 CONTROL。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = ::startDebugger,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !loading && serverSnapshot?.running != true,
                ) { Text("显式授权并启动 targetless LLDB server") }
            }
        }
        item {
            DebuggerCard("3. 第二层授权：连接并执行 typed attach") {
                expectedControlPhrase()?.let { phrase ->
                    Text("实际 attach 会改变目标运行状态，请再次输入：", fontWeight = FontWeight.SemiBold)
                    SelectionContainer { Text(phrase, fontFamily = FontFamily.Monospace) }
                }
                serverSnapshot?.let { server ->
                    Text(
                        "helperVerified=${server.helperVerified} serverReadyForClient=${server.serverReadyForClient}",
                        fontFamily = FontFamily.Monospace,
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
                ) { Text("授权连接 127.0.0.1 并发送 typed vAttach") }
                ControlSnapshot(controlSnapshot)
            }
        }
        item {
            DebuggerCard("4. 只读观察") {
                Text(
                    "线程是调试上下文的一部分。默认优先选择目标进程主线程；如果它正阻塞在 Looper/syscall，可手动选择其他线程再 STEP。",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = ::refreshThreads,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !loading && controlSnapshot.connected && !controlSnapshot.targetRunning,
                ) { Text("刷新线程列表（只读）") }
                Text(
                    "selectedThread=${controlSnapshot.selectedThreadId ?: "无"} threadCount=${controlSnapshot.threads.size}",
                    fontFamily = FontFamily.Monospace,
                )
                controlSnapshot.threads.take(MAX_THREAD_ROWS).forEach { thread ->
                    OutlinedButton(
                        onClick = { selectThread(thread.id) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !loading && !controlSnapshot.targetRunning &&
                            controlSnapshot.selectedThreadId != thread.id,
                    ) {
                        Text(
                            buildString {
                                if (controlSnapshot.selectedThreadId == thread.id) append("▶ ")
                                if (thread.isMain) append("[main] ")
                                append("TID=${thread.id} ")
                                append(thread.name ?: "<unnamed>")
                            },
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
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
            DebuggerCard("5. 自动代码上下文") {
                Text(
                    "每次可信 stop 后，AutoCrack 自动读取 PC/LR/SP/FP、/proc/<pid>/maps、最多 36B 的 PC 附近代码，并以 x29 frame record 做最多 16 帧的只读调用栈回溯；无 FP 时只退化为 PC/LR，不扫栈猜地址。",
                    style = MaterialTheme.typography.bodySmall,
                )
                controlSnapshot.codeContext?.let { code ->
                    Text(
                        "thread=${code.threadId} name=${code.threadName ?: "未知"}",
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        "PC=0x${code.pc.toString(16)} LR=${code.lr?.let { "0x${it.toString(16)}" } ?: "无"} SP=${code.sp?.let { "0x${it.toString(16)}" } ?: "无"} FP=${code.framePointer?.let { "0x${it.toString(16)}" } ?: "无"}",
                        fontFamily = FontFamily.Monospace,
                    )
                    SelectionContainer {
                        Text("module=${code.modulePath}", fontFamily = FontFamily.Monospace)
                    }
                    Text(
                        "base=0x${code.moduleBase.toString(16)} +0x${code.moduleOffset.toString(16)} segment=0x${code.segmentStart.toString(16)}-0x${code.segmentEndExclusive.toString(16)} ${code.segmentPermissions} fileOff=0x${code.segmentFileOffset.toString(16)}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "调用栈 frames=${code.stack.frames.size} partial=${code.stack.partial} termination=${code.stack.termination}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    code.stack.frames.forEach { frame ->
                        SelectionContainer {
                            Text(
                                "#${frame.index} 0x${frame.address.toString(16)} ${frame.modulePath}+0x${frame.moduleOffset.toString(16)} fp=${frame.framePointer?.let { "0x${it.toString(16)}" } ?: "无"} source=${frame.source}",
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    Text("指令上下文（${code.decoder}）", fontWeight = FontWeight.SemiBold)
                    code.instructions.forEach { instruction ->
                        Text(
                            "${if (instruction.current) "→" else " "} 0x${instruction.address.toString(16)}  ${instruction.rawHex}  ${instruction.text}",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (instruction.current) FontWeight.Bold else FontWeight.Normal,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } ?: Text("尚未捕获：先使用自动运行/Continue 命中一次 stop，或完成一次 STEP。")
                controlSnapshot.codeContextFailure?.let { failure ->
                    Text("codeContextFailure=$failure", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        item {
            DebuggerCard("6. 受控硬件执行断点") {
                Text(
                    "仅支持 AArch64 hardware execution breakpoint：固定 Z1/z1、4 字节对齐、单会话最多跟踪 8 个；不使用会改写代码页的 Z0 软件断点。",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = breakpointAddressText,
                    onValueChange = { breakpointAddressText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("执行断点地址（hex，例如 0x7abc...）") },
                    singleLine = true,
                )
                Button(
                    onClick = ::setHardwareBreakpoint,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !loading && controlSnapshot.connected && !controlSnapshot.targetRunning,
                ) { Text("设置 typed 硬件执行断点") }
                Text(
                    "trackedBreakpoints=${controlSnapshot.breakpoints.size}",
                    fontFamily = FontFamily.Monospace,
                )
                controlSnapshot.breakpoints.forEach { breakpoint ->
                    OutlinedButton(
                        onClick = { removeHardwareBreakpoint(breakpoint.address) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !loading && !controlSnapshot.targetRunning,
                    ) {
                        Text(
                            "移除 HW 0x${breakpoint.address.toString(16)} kind=${breakpoint.kindBytes} hits=${breakpoint.hitCount} lastThread=${breakpoint.lastHitThreadId ?: "无"}${if (breakpoint.autoManaged) " [auto]" else ""}",
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
        item {
            DebuggerCard("7. 自动进入可单步位置 / 执行控制") {
                Text(
                    "推荐直接使用自动模式：AutoCrack 优先选择主线程，读取其当前 PC，设置一次性 Z1 硬件执行锚点并 Continue；下一次 stop 会自动选择真正停止的线程并清理该锚点。无需手工填写地址或挑线程。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = ::autoRunToSteppablePosition,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !loading && controlSnapshot.connected && !controlSnapshot.targetRunning,
                ) { Text("自动选择线程 + 地址并运行") }
                OutlinedButton(
                    onClick = ::interruptTarget,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !loading && controlSnapshot.connected && controlSnapshot.targetRunning,
                ) { Text("暂停运行并抓取当前上下文") }
                Text(
                    "autoPrepared=${controlSnapshot.autoAnchorPrepared} autoThread=${controlSnapshot.autoAnchorThreadId ?: "无"} autoPC=${controlSnapshot.autoAnchorAddress?.let { "0x${it.toString(16)}" } ?: "无"} stopObserved=${controlSnapshot.autoAnchorStopObserved} autoResumed=${controlSnapshot.autoAnchorAutoResumed} signalPass=${controlSnapshot.autoSignalPassthroughCount}",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("自动模式推荐流程：启动自动观察 → 切到目标 App 正常操作 → 回来点“暂停运行并抓取当前上下文” → 再 STEP。时间线会保留中间 stop，而不是只展示最后状态。")
                Text(
                    "timeline=${controlSnapshot.timeline.size} latest=${controlSnapshot.timeline.lastOrNull()?.kind ?: "无"}",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = ::stepTarget,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !loading && controlSnapshot.connected && !controlSnapshot.targetRunning &&
                        controlSnapshot.selectedThreadId != null,
                ) { Text("Single step selected thread") }
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
            DebuggerCard("8. 状态与安全 detach") {
                OutlinedButton(onClick = ::refreshDebugger, modifier = Modifier.fillMaxWidth(), enabled = !loading) {
                    Text("刷新 TracerPid / helper / listener / client 状态")
                }
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
                Text("control 审计固定记录 registerWrite=false / memoryWrite=false / rawPacket=false；breakpoint 仅允许 typed hardware execution。")
                Button(onClick = ::copyDiagnostics, modifier = Modifier.fillMaxWidth()) {
                    Text("复制完整 Debugger 诊断")
                }
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
        "threads=${snapshot.threads.size} selected=${snapshot.selectedThreadId ?: "无"} lastStepThread=${snapshot.lastStepThreadId ?: "无"}",
        fontFamily = FontFamily.Monospace,
    )
    Text(
        "continue=${snapshot.continueCommandSent} step=${snapshot.stepCommandSent} stepCompleted=${snapshot.stepCompleted} stepAutoInterruptRecovered=${snapshot.stepAutoInterruptRecovered} interrupt=${snapshot.interruptCommandSent}",
        fontFamily = FontFamily.Monospace,
    )
    Text(
        "registerRead=${snapshot.registerReadCommandSent} memoryRead=${snapshot.memoryReadCommandSent}",
        fontFamily = FontFamily.Monospace,
    )
    Text(
        "registerWrite=${snapshot.registerWriteCommandSent} memoryWrite=${snapshot.memoryWriteCommandSent} breakpoint=${snapshot.breakpointCommandSent} hwBreakpoints=${snapshot.breakpoints.size}",
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
        "helperVerified=${snapshot.helperVerified} serverReadyForClient=${snapshot.serverReadyForClient}",
        fontFamily = FontFamily.Monospace,
    )
    snapshot.helperCommandLine?.let {
        Text("helperCmdline=$it", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
    Text(
        "attachAttempted=${snapshot.attachAttempted} attachedObserved=${snapshot.attachedObserved} tracerBefore=${snapshot.tracerPidBefore} tracerCurrent=${snapshot.tracerPidCurrent ?: "未知"}",
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
        SelectionContainer { Text(snapshot.stdout.takeLast(MAX_OUTPUT_CHARS), fontFamily = FontFamily.Monospace) }
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
    appendLine("边界：loopback only；寄存器/内存只读；允许显式授权 typed attach / continue / step / interrupt / AArch64 hardware execution breakpoint；支持自动 main-thread PC one-shot anchor；自动运行期间 signal 只可从同一 trusted stop 提取并仅交给该 stopped thread，其他线程 plain continue；SIGTRAP 仅在 reason:signal 时按目标信号处理；无 arbitrary signal / software breakpoint / register write / memory write / raw packet adapter")
    server?.let { result ->
        appendLine()
        appendLine("[server] session=${result.sessionId}")
        appendLine("package=${result.packageName} pid=${result.pid} port=${result.port}")
        appendLine("running=${result.running} exit=${result.exitCode ?: "无"} failure=${result.failure ?: "无"}")
        appendLine("helperPid=${result.helperPid ?: "无"} helperVerified=${result.helperVerified} serverReadyForClient=${result.serverReadyForClient}")
        appendLine("helperCmdline=${result.helperCommandLine ?: "无"}")
        appendLine("helperSignalSent=${result.helperSignalSent}")
        appendLine("explicitAuthorizationVerified=${result.explicitAuthorizationVerified}")
        appendLine("attachAttempted=${result.attachAttempted} attachedObserved=${result.attachedObserved}")
        appendLine("tracerPidBefore=${result.tracerPidBefore} tracerPidCurrent=${result.tracerPidCurrent ?: "未知"}")
        appendLine("targetStateChanged=${result.targetStateChanged} detachVerified=${result.detachVerified}")
        appendLine("targetSignalAttempted=${result.targetSignalAttempted}")
        if (result.stdout.isNotBlank()) appendLine("serverStdout=${result.stdout.takeLast(MAX_DIAGNOSTIC_OUTPUT_CHARS)}")
        if (result.stderr.isNotBlank()) appendLine("serverStderr=${result.stderr.takeLast(MAX_DIAGNOSTIC_OUTPUT_CHARS)}")
    }
    appendLine()
    appendLine("[client-control]")
    appendLine("session=${control.sessionId ?: "无"} package=${control.packageName ?: "无"} pid=${control.pid ?: "无"} port=${control.port ?: "无"}")
    appendLine("controlAuthorizationVerified=${control.controlAuthorizationVerified}")
    appendLine("clientConnected=${control.connected} targetRunning=${control.targetRunning}")
    appendLine("lastStopReply=${control.lastStopReply ?: "无"}")
    appendLine("capabilityCount=${control.capabilities.size}")
    appendLine("threadListCommandSent=${control.threadListCommandSent} threadCount=${control.threads.size}")
    appendLine("selectedThreadId=${control.selectedThreadId ?: "无"}")
    appendLine("lastStepThreadId=${control.lastStepThreadId ?: "无"}")
    appendLine("continueCommandSent=${control.continueCommandSent}")
    appendLine("stepCommandSent=${control.stepCommandSent}")
    appendLine("stepCompleted=${control.stepCompleted}")
    appendLine("stepAutoInterruptRecovered=${control.stepAutoInterruptRecovered}")
    appendLine("interruptCommandSent=${control.interruptCommandSent}")
    appendLine("registerReadCommandSent=${control.registerReadCommandSent} registerCount=${control.registers.size}")
    appendLine("memoryReadCommandSent=${control.memoryReadCommandSent} memoryAddress=${control.lastMemoryAddress?.let { "0x${it.toString(16)}" } ?: "无"}")
    appendLine("registerWriteCommandSent=${control.registerWriteCommandSent}")
    appendLine("memoryWriteCommandSent=${control.memoryWriteCommandSent}")
    appendLine("breakpointCommandSent=${control.breakpointCommandSent}")
    appendLine("breakpointSetCommandSent=${control.breakpointSetCommandSent}")
    appendLine("breakpointRemoveCommandSent=${control.breakpointRemoveCommandSent}")
    appendLine("breakpointCount=${control.breakpoints.size}")
    appendLine("breakpointHitCount=${control.breakpoints.sumOf { it.hitCount }}")
    appendLine("autoAnchorPrepared=${control.autoAnchorPrepared}")
    appendLine("autoAnchorThreadId=${control.autoAnchorThreadId ?: "无"}")
    appendLine("autoAnchorAddress=${control.autoAnchorAddress?.let { "0x${it.toString(16)}" } ?: "无"}")
    appendLine("autoAnchorStopObserved=${control.autoAnchorStopObserved}")
    appendLine("codeContextCaptured=${control.codeContext != null}")
    appendLine("codeContextFailure=${control.codeContextFailure ?: "无"}")
    appendLine("autoAnchorAutoResumed=${control.autoAnchorAutoResumed}")
    appendLine("autoSignalPassthroughCount=${control.autoSignalPassthroughCount}")
    appendLine("lastAutoPassedSignal=${control.lastAutoPassedSignal?.let { "0x${it.toString(16).padStart(2, '0')}" } ?: "无"}")
    appendLine("autoSignalPassthroughScope=stopped_thread_only")
    appendLine("explicitSignalReasonTrapPassthrough=true")
    control.codeContext?.let { code ->
        appendLine("codeThreadId=${code.threadId} codeThreadName=${code.threadName ?: "无"}")
        appendLine("codePC=0x${code.pc.toString(16)} codeLR=${code.lr?.let { "0x${it.toString(16)}" } ?: "无"} codeSP=${code.sp?.let { "0x${it.toString(16)}" } ?: "无"} codeFP=${code.framePointer?.let { "0x${it.toString(16)}" } ?: "无"}")
        appendLine("codeModule=${code.modulePath}")
        appendLine("codeModuleBase=0x${code.moduleBase.toString(16)} codeModuleOffset=0x${code.moduleOffset.toString(16)}")
        appendLine("codeSegment=0x${code.segmentStart.toString(16)}-0x${code.segmentEndExclusive.toString(16)} perms=${code.segmentPermissions} fileOffset=0x${code.segmentFileOffset.toString(16)}")
        appendLine("codeDecoder=${code.decoder}")
        appendLine("callStackFrames=${code.stack.frames.size} partial=${code.stack.partial} termination=${code.stack.termination}")
        code.stack.frames.forEach { frame ->
            appendLine("  frame#${frame.index} pc=0x${frame.address.toString(16)} module=${frame.modulePath}+0x${frame.moduleOffset.toString(16)} fp=${frame.framePointer?.let { "0x${it.toString(16)}" } ?: "无"} source=${frame.source}")
        }
        appendLine("instructions:")
        code.instructions.forEach { instruction ->
            appendLine("  ${if (instruction.current) "->" else "  "} 0x${instruction.address.toString(16)} ${instruction.rawHex} ${instruction.text}")
        }
    }
    appendLine("timelineCount=${control.timeline.size}")
    appendLine("timeline:")
    control.timeline.forEach { event ->
        appendLine(
            "  #${event.sequence} t=${event.timestampEpochMillis} kind=${event.kind} thread=${event.threadId ?: "无"} " +
                "pc=${event.pc?.let { "0x${it.toString(16)}" } ?: "无"} " +
                "module=${event.modulePath ?: "无"} offset=${event.moduleOffset?.let { "+0x${it.toString(16)}" } ?: "无"} " +
                "insn=${event.instructionRawHex ?: "无"}:${event.instructionText ?: "无"} summary=${event.summary}",
        )
    }
    appendLine("rawPacketAdapterExposed=false")
    appendLine("controlFailure=${control.failure ?: "无"}")
    if (control.breakpoints.isNotEmpty()) {
        appendLine("breakpoints:")
        control.breakpoints.forEach {
            appendLine("  hardware_execution address=0x${it.address.toString(16)} kind=${it.kindBytes} hits=${it.hitCount} lastThread=${it.lastHitThreadId ?: "无"} lastHitAt=${it.lastHitAtEpochMillis ?: "无"} autoManaged=${it.autoManaged}")
        }
    }
    if (control.registers.isNotEmpty()) {
        appendLine("registers:")
        control.registers.forEach {
            appendLine("  #${it.index} ${it.name} ${it.bitSize ?: "?"}bit=${it.rawHex}")
        }
    }
    control.lastMemoryHex?.let { appendLine("memoryHex=$it") }
}

private const val MAX_PROCESS_ROWS = 24
private const val MAX_THREAD_ROWS = 32
private const val MAX_REGISTER_ROWS = 48
private const val MAX_OUTPUT_CHARS = 8_000
private const val MAX_DIAGNOSTIC_OUTPUT_CHARS = 4_000
