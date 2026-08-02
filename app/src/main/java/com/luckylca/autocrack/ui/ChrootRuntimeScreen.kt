package com.luckylca.autocrack.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.luckylca.autocrack.runtime.ChrootRuntimeEngine
import com.luckylca.autocrack.runtime.RootShellRuntimeEngine
import com.luckylca.autocrack.runtime.RootfsInstallResult
import com.luckylca.autocrack.runtime.RootfsPackageInstaller
import com.luckylca.autocrack.runtime.RuntimeLayout
import com.luckylca.autocrack.runtime.RuntimeRootfsState
import com.luckylca.autocrack.runtime.ShellCommandRequest
import com.luckylca.autocrack.runtime.ShellCommandResult
import kotlinx.coroutines.launch

@Composable
fun ChrootRuntimeScreen() {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val layout = remember(appContext) { RuntimeLayout(appContext).initialize() }
    val workspace = remember(layout) { layout.createRuntimeWorkspace() }
    val hostEngine = remember(layout) { RootShellRuntimeEngine(layout) }
    val chrootEngine = remember(layout, hostEngine) { ChrootRuntimeEngine(layout, hostEngine) }
    val installer = remember(appContext, layout) { RootfsPackageInstaller(appContext, layout) }
    val scope = rememberCoroutineScope()

    var refreshKey by remember { mutableIntStateOf(0) }
    var rootfsState by remember { mutableStateOf(RuntimeRootfsState.NOT_INSTALLED) }
    var rootfsVersion by remember { mutableStateOf<String?>(null) }
    var selectedPackageName by remember { mutableStateOf<String?>(null) }
    var installRunning by remember { mutableStateOf(false) }
    var installStatus by remember { mutableStateOf("请选择 AutoCrackApp Debian rootfs 包") }
    var installResult by remember { mutableStateOf<RootfsInstallResult?>(null) }

    var command by remember {
        mutableStateOf(
            "id; printf 'BASH=%s\\n' \"${'$'}BASH_VERSION\"; uname -m; " +
                "cat /etc/os-release; printf 'PWD='; pwd; " +
                "printf 'TOOLS:\\n'; command -v bash file readelf python3 unzip xz zstd; " +
                "printf 'WORKSPACE:\\n'; ls -la /workspace",
        )
    }
    var timeoutText by remember { mutableStateOf("120000") }
    var commandRunning by remember { mutableStateOf(false) }
    var commandResult by remember { mutableStateOf<ShellCommandResult?>(null) }
    var commandError by remember { mutableStateOf<String?>(null) }
    var cleanupStatus by remember { mutableStateOf("尚未执行挂载清理") }

    LaunchedEffect(refreshKey) {
        rootfsState = layout.readRootfsState()
        rootfsVersion = layout.readRootfsVersion()
    }

    val packageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            selectedPackageName = queryDisplayName(context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )) ?: uri.lastPathSegment
            scope.launch {
                installRunning = true
                installResult = null
                installStatus = "开始安装 rootfs"
                runCatching {
                    installer.install(uri) { progress -> installStatus = progress }
                }.onSuccess { result ->
                    installResult = result
                    installStatus = "安装完成：${result.manifest.version}"
                    refreshKey += 1
                }.onFailure { exception ->
                    installStatus = "安装失败：${exception.message ?: exception::class.java.name}"
                    refreshKey += 1
                }
                installRunning = false
            }
        }
    }

    fun runChrootCommand() {
        val timeout = timeoutText.toLongOrNull()
        if (timeout == null) {
            commandError = "超时必须是毫秒整数"
            return
        }
        scope.launch {
            commandRunning = true
            commandResult = null
            commandError = null
            runCatching {
                chrootEngine.execute(
                    ShellCommandRequest(
                        command = command,
                        workingDirectory = "/workspace",
                        timeoutMillis = timeout,
                        environment = mapOf(
                            "AUTOC_APP_VERSION" to BuildConfig.VERSION_NAME,
                            "AUTOC_HOST_WORKSPACE" to workspace.path,
                        ),
                    ),
                )
            }.onSuccess { result -> commandResult = result }
                .onFailure { exception ->
                    commandError = exception.message ?: exception::class.java.name
                }
            commandRunning = false
        }
    }

    fun copyDiagnostics() {
        val report = buildChrootDiagnostic(
            layout = layout,
            state = rootfsState,
            version = rootfsVersion,
            selectedPackage = selectedPackageName,
            installStatus = installStatus,
            installResult = installResult,
            commandResult = commandResult,
            commandError = commandError,
            cleanupStatus = cleanupStatus,
        )
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
            ClipData.newPlainText("AutoCrackApp Debian chroot 诊断", report),
        )
        Toast.makeText(context, "Debian chroot 诊断已复制", Toast.LENGTH_SHORT).show()
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
                "Debian Root Runtime",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "${BuildConfig.VERSION_NAME} · arm64 chroot · KernelSU",
                color = MaterialTheme.colorScheme.primary,
            )
        }

        item {
            ChrootCard("Rootfs 安装与状态") {
                ChrootInfoRow("状态", rootfsState.name)
                ChrootInfoRow("版本", rootfsVersion ?: "未安装")
                ChrootInfoRow("安装目录", layout.rootfsRoot.path)
                ChrootInfoRow("所选包", selectedPackageName ?: "未选择")
                if (installRunning) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(installStatus, fontFamily = FontFamily.Monospace)
                installResult?.let { result ->
                    ChrootInfoRow("包 ID", result.manifest.id)
                    ChrootInfoRow("压缩层", "${result.archiveBytes} B")
                    ChrootInfoRow("解包条目", result.extractedEntries.toString())
                    ChrootInfoRow("解包数据", "${result.extractedBytes} B")
                    ChrootInfoRow("安装耗时", "${result.durationMillis} ms")
                }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !installRunning && !commandRunning,
                    onClick = {
                        packageLauncher.launch(
                            arrayOf("application/zip", "application/octet-stream"),
                        )
                    },
                ) {
                    Text(if (rootfsState == RuntimeRootfsState.INSTALLED) "选择并更新 rootfs" else "选择并安装 rootfs")
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        enabled = !installRunning && !commandRunning,
                        onClick = {
                            scope.launch {
                                cleanupStatus = runCatching {
                                    val result = chrootEngine.cleanupMounts()
                                    "清理完成：exit=${result.exitCode}, ${result.stdout}"
                                }.getOrElse { "清理失败：${it.message}" }
                            }
                        },
                    ) {
                        Text("清理挂载")
                    }
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        enabled = !installRunning && !commandRunning &&
                            rootfsState != RuntimeRootfsState.NOT_INSTALLED,
                        onClick = {
                            scope.launch {
                                installRunning = true
                                runCatching {
                                    chrootEngine.cleanupMounts()
                                    installer.uninstall { progress -> installStatus = progress }
                                }.onFailure { exception ->
                                    installStatus = "卸载失败：${exception.message}"
                                }
                                installRunning = false
                                refreshKey += 1
                            }
                        },
                    ) {
                        Text("卸载 rootfs")
                    }
                }
                Text(cleanupStatus, style = MaterialTheme.typography.bodySmall)
            }
        }

        item {
            ChrootCard("Debian Bash 一次性执行") {
                Text(
                    "命令运行在 chroot 内的 /bin/bash；/workspace 映射到 AutoCrackApp 管理工作区。当前尚未提供 PTY，交互式 GDB/LLDB 会在下一阶段接入。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = command,
                    onValueChange = { command = it },
                    label = { Text("Bash 命令") },
                    minLines = 5,
                    maxLines = 14,
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
                        enabled = rootfsState == RuntimeRootfsState.INSTALLED &&
                            !commandRunning && command.isNotBlank(),
                        onClick = ::runChrootCommand,
                    ) {
                        Text("运行 Debian Bash")
                    }
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        enabled = commandRunning,
                        onClick = {
                            val count = hostEngine.cancelAll()
                            commandError = "已请求取消 $count 个宿主进程"
                        },
                    ) {
                        Text("取消")
                    }
                }
                if (commandRunning) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                commandError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                commandResult?.let { ChrootResultView(it) }
            }
        }

        item {
            ChrootCard("诊断与审计") {
                ChrootInfoRow("Host Shell 审计", layout.shellAuditFile.path)
                ChrootInfoRow("Chroot 审计", layout.chrootAuditFile.path)
                ChrootInfoRow("Workspace", workspace.path)
                Button(modifier = Modifier.fillMaxWidth(), onClick = ::copyDiagnostics) {
                    Text("复制完整 Debian chroot 诊断")
                }
            }
        }

        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun ChrootResultView(result: ShellCommandResult) {
    HorizontalDivider()
    ChrootInfoRow("请求 ID", result.requestId)
    ChrootInfoRow("退出码", result.exitCode?.toString() ?: "无")
    ChrootInfoRow("耗时", "${result.durationMillis} ms")
    ChrootInfoRow("超时 / 取消", "${result.timedOut} / ${result.cancelled}")
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
private fun ChrootCard(title: String, content: @Composable () -> Unit) {
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
private fun ChrootInfoRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value)
    }
}

private fun queryDisplayName(cursor: Cursor?): String? = cursor?.use {
    if (!it.moveToFirst()) return@use null
    val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
    if (index < 0) null else it.getString(index)
}

private fun buildChrootDiagnostic(
    layout: RuntimeLayout,
    state: RuntimeRootfsState,
    version: String?,
    selectedPackage: String?,
    installStatus: String,
    installResult: RootfsInstallResult?,
    commandResult: ShellCommandResult?,
    commandError: String?,
    cleanupStatus: String,
): String = buildString {
    appendLine("AutoCrackApp Debian Root Runtime 诊断")
    appendLine("版本：${BuildConfig.VERSION_NAME}")
    appendLine("Rootfs 状态：$state")
    appendLine("Rootfs 版本：${version ?: "无"}")
    appendLine("Rootfs 目录：${layout.rootfsRoot.path}")
    appendLine("工作区：${layout.createRuntimeWorkspace().path}")
    appendLine()
    appendLine("1. 安装")
    appendLine("   所选包：${selectedPackage ?: "无"}")
    appendLine("   状态：$installStatus")
    installResult?.let { result ->
        appendLine("   ID：${result.manifest.id}")
        appendLine("   SHA-256：${result.manifest.archiveSha256}")
        appendLine("   压缩大小：${result.archiveBytes} B")
        appendLine("   条目：${result.extractedEntries}")
        appendLine("   解包大小：${result.extractedBytes} B")
        appendLine("   耗时：${result.durationMillis} ms")
    }
    appendLine()
    appendLine("2. Chroot Bash")
    if (commandResult == null) {
        appendLine("   未完成：${commandError ?: "未执行"}")
    } else {
        appendLine("   请求 ID：${commandResult.requestId}")
        appendLine("   命令：${commandResult.command}")
        appendLine("   退出码：${commandResult.exitCode}")
        appendLine("   耗时：${commandResult.durationMillis} ms")
        appendLine("   超时：${commandResult.timedOut}")
        appendLine("   取消：${commandResult.cancelled}")
        appendLine("   failure：${commandResult.failure ?: "无"}")
        appendLine("   stdout：")
        appendLine(commandResult.stdout.take(MAX_CHROOT_DIAGNOSTIC_CHARS))
        appendLine("   stderr：")
        appendLine(commandResult.stderr.take(MAX_CHROOT_DIAGNOSTIC_CHARS))
    }
    appendLine()
    appendLine("3. 挂载清理")
    appendLine("   $cleanupStatus")
    appendLine()
    appendLine("4. 审计")
    appendLine("   ${layout.chrootAuditFile.path}")
}

private const val MAX_CHROOT_DIAGNOSTIC_CHARS = 30_000
