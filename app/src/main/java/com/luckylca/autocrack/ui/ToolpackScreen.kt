package com.luckylca.autocrack.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.database.Cursor
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
import com.luckylca.autocrack.runtime.InstalledToolpack
import com.luckylca.autocrack.runtime.RootShellRuntimeEngine
import com.luckylca.autocrack.runtime.RuntimeLayout
import com.luckylca.autocrack.runtime.RuntimeRootfsState
import com.luckylca.autocrack.runtime.ToolpackInstallResult
import com.luckylca.autocrack.runtime.ToolpackPackageInstaller
import com.luckylca.autocrack.runtime.ToolpackSelfTestReport
import kotlinx.coroutines.launch

@Composable
fun ToolpackScreen() {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val layout = remember(appContext) { RuntimeLayout(appContext).initialize() }
    val hostEngine = remember(layout) { RootShellRuntimeEngine(layout) }
    val chrootEngine = remember(layout, hostEngine) { ChrootRuntimeEngine(layout, hostEngine) }
    val installer = remember(appContext, layout) { ToolpackPackageInstaller(appContext, layout) }
    val scope = rememberCoroutineScope()

    var refreshKey by remember { mutableIntStateOf(0) }
    var rootfsState by remember { mutableStateOf(RuntimeRootfsState.NOT_INSTALLED) }
    var installed by remember { mutableStateOf<List<InstalledToolpack>>(emptyList()) }
    var selectedPackageName by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("请选择 AutoCrackApp 工具包") }
    var installResult by remember { mutableStateOf<ToolpackInstallResult?>(null) }
    var selfTestReport by remember { mutableStateOf<ToolpackSelfTestReport?>(null) }

    LaunchedEffect(refreshKey) {
        rootfsState = layout.readRootfsState()
        installed = installer.listInstalled()
    }

    val packageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            selectedPackageName = queryToolpackDisplayName(
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null,
                ),
            ) ?: uri.lastPathSegment
            scope.launch {
                running = true
                installResult = null
                selfTestReport = null
                status = "准备安装工具包"
                runCatching {
                    chrootEngine.cleanupMounts()
                    installer.install(uri) { progress -> status = progress }
                }.onSuccess { result ->
                    installResult = result
                    status = "安装完成：${result.manifest.title} ${result.manifest.version}"
                    refreshKey += 1
                }.onFailure { exception ->
                    status = "安装失败：${exception.message ?: exception::class.java.name}"
                }
                running = false
            }
        }
    }

    fun runSelfTests(toolpack: InstalledToolpack) {
        scope.launch {
            running = true
            selfTestReport = null
            status = "开始执行 ${toolpack.manifest.title} 自检"
            runCatching {
                installer.runSelfTests(toolpack, chrootEngine) { progress -> status = progress }
            }.onSuccess { report ->
                selfTestReport = report
                status = if (report.passed) "工具包自检全部通过" else "工具包自检存在失败"
            }.onFailure { exception ->
                status = "自检失败：${exception.message ?: exception::class.java.name}"
            }
            running = false
        }
    }

    fun uninstall(toolpack: InstalledToolpack) {
        scope.launch {
            running = true
            selfTestReport = null
            runCatching {
                chrootEngine.cleanupMounts()
                installer.uninstall(toolpack.manifest.id) { progress -> status = progress }
            }.onSuccess {
                refreshKey += 1
            }.onFailure { exception ->
                status = "卸载失败：${exception.message ?: exception::class.java.name}"
            }
            running = false
        }
    }

    fun copyDiagnostics() {
        val report = buildToolpackDiagnostics(
            layout = layout,
            rootfsState = rootfsState,
            selectedPackageName = selectedPackageName,
            status = status,
            installResult = installResult,
            installed = installed,
            selfTestReport = selfTestReport,
        )
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
            ClipData.newPlainText("AutoCrackApp 工具包诊断", report),
        )
        Toast.makeText(context, "工具包诊断已复制", Toast.LENGTH_SHORT).show()
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
                "Reverse Toolpacks",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "${BuildConfig.VERSION_NAME} · 校验 SHA-256 · 版本化安装 · Chroot 自检",
                color = MaterialTheme.colorScheme.primary,
            )
        }

        item {
            ToolpackCard("导入工具包") {
                ToolpackInfoRow("Rootfs 状态", rootfsState.name)
                ToolpackInfoRow("所选包", selectedPackageName ?: "未选择")
                ToolpackInfoRow("工具包目录", layout.toolpacksRoot.path)
                if (running) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(status, fontFamily = FontFamily.Monospace)
                installResult?.let { result ->
                    ToolpackInfoRow("ID", result.manifest.id)
                    ToolpackInfoRow("版本", result.manifest.version)
                    ToolpackInfoRow("Payload SHA-256", result.manifest.payloadSha256)
                    ToolpackInfoRow("解包条目", result.extractedEntries.toString())
                    ToolpackInfoRow("解包大小", "${result.extractedBytes} B")
                    ToolpackInfoRow("耗时", "${result.durationMillis} ms")
                }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !running && rootfsState == RuntimeRootfsState.INSTALLED,
                    onClick = {
                        packageLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                    },
                ) {
                    Text("选择并安装工具包")
                }
                if (rootfsState != RuntimeRootfsState.INSTALLED) {
                    Text(
                        "必须先在 Linux 页面安装 Debian rootfs。",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        if (installed.isEmpty()) {
            item {
                ToolpackCard("已安装工具包") {
                    Text("尚未安装工具包")
                }
            }
        } else {
            installed.forEach { toolpack ->
                item(key = toolpack.manifest.id) {
                    ToolpackCard(toolpack.manifest.title) {
                        ToolpackInfoRow("ID", toolpack.manifest.id)
                        ToolpackInfoRow("版本", toolpack.manifest.version)
                        ToolpackInfoRow("架构", toolpack.manifest.architecture)
                        ToolpackInfoRow(
                            "命令",
                            toolpack.manifest.commands.joinToString { command -> command.name },
                        )
                        ToolpackInfoRow("安装目录", toolpack.installedPath)
                        ToolpackInfoRow("对应 Rootfs", toolpack.rootfsVersion ?: "未知")
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                modifier = Modifier.weight(1f),
                                enabled = !running,
                                onClick = { runSelfTests(toolpack) },
                            ) {
                                Text("运行自检")
                            }
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                enabled = !running,
                                onClick = { uninstall(toolpack) },
                            ) {
                                Text("卸载")
                            }
                        }
                    }
                }
            }
        }

        selfTestReport?.let { report ->
            item {
                ToolpackCard("自检结果：${report.manifest.title}") {
                    ToolpackInfoRow("总体", if (report.passed) "通过" else "失败")
                    report.results.forEach { result ->
                        HorizontalDivider()
                        Text(result.test.title, fontWeight = FontWeight.SemiBold)
                        ToolpackInfoRow("命令", result.test.command)
                        ToolpackInfoRow("退出码", result.commandResult.exitCode?.toString() ?: "无")
                        ToolpackInfoRow("结果", if (result.passed) "通过" else "失败")
                        result.failure?.let { failure ->
                            Text(failure, color = MaterialTheme.colorScheme.error)
                        }
                        val output = (result.commandResult.stdout + result.commandResult.stderr)
                            .trim()
                            .take(2_000)
                        if (output.isNotBlank()) {
                            Text(output, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }

        item {
            ToolpackCard("诊断与审计") {
                ToolpackInfoRow(
                    "审计文件",
                    "${layout.toolpacksRoot.path}/toolpack-audit.jsonl",
                )
                Button(modifier = Modifier.fillMaxWidth(), onClick = ::copyDiagnostics) {
                    Text("复制完整工具包诊断")
                }
            }
        }

        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun ToolpackCard(title: String, content: @Composable () -> Unit) {
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
private fun ToolpackInfoRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value)
    }
}

private fun queryToolpackDisplayName(cursor: Cursor?): String? = cursor?.use {
    if (!it.moveToFirst()) return@use null
    val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
    if (index < 0) null else it.getString(index)
}

private fun buildToolpackDiagnostics(
    layout: RuntimeLayout,
    rootfsState: RuntimeRootfsState,
    selectedPackageName: String?,
    status: String,
    installResult: ToolpackInstallResult?,
    installed: List<InstalledToolpack>,
    selfTestReport: ToolpackSelfTestReport?,
): String = buildString {
    appendLine("AutoCrackApp Reverse Toolpack 诊断")
    appendLine("版本：${BuildConfig.VERSION_NAME}")
    appendLine("Rootfs 状态：$rootfsState")
    appendLine("Rootfs 版本：${layout.readRootfsVersion() ?: "无"}")
    appendLine("所选包：${selectedPackageName ?: "无"}")
    appendLine("状态：$status")
    appendLine("工具包目录：${layout.toolpacksRoot.path}")
    appendLine("审计：${layout.toolpacksRoot.path}/toolpack-audit.jsonl")
    installResult?.let { result ->
        appendLine()
        appendLine("最近安装：")
        appendLine("  ID：${result.manifest.id}")
        appendLine("  版本：${result.manifest.version}")
        appendLine("  Payload SHA-256：${result.manifest.payloadSha256}")
        appendLine("  Payload：${result.payloadBytes} B")
        appendLine("  解包条目：${result.extractedEntries}")
        appendLine("  解包大小：${result.extractedBytes} B")
        appendLine("  耗时：${result.durationMillis} ms")
    }
    appendLine()
    appendLine("已安装数量：${installed.size}")
    installed.forEach { toolpack ->
        appendLine(
            "  ${toolpack.manifest.id} ${toolpack.manifest.version} " +
                "commands=${toolpack.manifest.commands.joinToString { it.name }}",
        )
    }
    selfTestReport?.let { report ->
        appendLine()
        appendLine("自检总体：${if (report.passed) "PASS" else "FAIL"}")
        report.results.forEach { result ->
            appendLine(
                "  ${result.test.id}: passed=${result.passed} " +
                    "exit=${result.commandResult.exitCode} failure=${result.failure ?: "无"}",
            )
        }
    }
}
