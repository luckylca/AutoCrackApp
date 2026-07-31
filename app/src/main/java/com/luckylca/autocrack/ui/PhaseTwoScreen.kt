package com.luckylca.autocrack.ui

import android.os.Build
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.luckylca.autocrack.apk.ApkArtifactKind
import com.luckylca.autocrack.apk.ExtractionReport
import com.luckylca.autocrack.apk.InstalledApp
import com.luckylca.autocrack.apk.InstalledAppKind
import com.luckylca.autocrack.apk.PackageRepository
import com.luckylca.autocrack.root.ProcessRootCommandRunner
import com.luckylca.autocrack.root.RootAccessState
import com.luckylca.autocrack.root.RootDetector
import com.luckylca.autocrack.root.RootProvider
import com.luckylca.autocrack.root.RootStatus
import com.luckylca.autocrack.root.UnixIdentity
import kotlinx.coroutines.launch

private sealed interface AppListUiState {
    data object Loading : AppListUiState
    data class Ready(val apps: List<InstalledApp>) : AppListUiState
    data class Error(val message: String) : AppListUiState
}

private sealed interface ExtractionUiState {
    data object Idle : ExtractionUiState
    data class Running(val packageName: String) : ExtractionUiState
    data class Success(val report: ExtractionReport) : ExtractionUiState
    data class Error(val packageName: String, val message: String) : ExtractionUiState
}

@Composable
fun PhaseTwoScreen() {
    val context = LocalContext.current.applicationContext
    val inspectionMode = LocalInspectionMode.current
    val runner = remember { ProcessRootCommandRunner() }
    val detector = remember(runner) { RootDetector(runner) }
    val repository = remember(context, runner) { PackageRepository(context, runner) }
    val scope = rememberCoroutineScope()

    var refreshKey by remember { mutableIntStateOf(0) }
    var rootStatus by remember { mutableStateOf<RootStatus?>(null) }
    var appListState by remember { mutableStateOf<AppListUiState>(AppListUiState.Loading) }
    var extractionState by remember { mutableStateOf<ExtractionUiState>(ExtractionUiState.Idle) }
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(refreshKey, inspectionMode) {
        rootStatus = null
        appListState = AppListUiState.Loading
        extractionState = ExtractionUiState.Idle

        val status = if (inspectionMode) previewRootStatus() else detector.inspect()
        rootStatus = status
        appListState = if (!status.isRootGranted) {
            AppListUiState.Error(status.diagnostic ?: "Root 未授权，无法读取应用列表")
        } else {
            try {
                val apps = if (inspectionMode) previewApps() else repository.listInstalledApps(status)
                AppListUiState.Ready(apps)
            } catch (exception: Exception) {
                AppListUiState.Error(exception.message ?: "读取应用列表时发生未知错误")
            }
        }
    }

    val allApps = (appListState as? AppListUiState.Ready)?.apps.orEmpty()
    val filteredApps = remember(allApps, searchQuery) {
        val query = searchQuery.trim()
        if (query.isBlank()) {
            allApps
        } else {
            allApps.filter { app ->
                app.packageName.contains(query, ignoreCase = true) ||
                    app.primaryApkPath.orEmpty().contains(query, ignoreCase = true)
            }
        }
    }
    val extractionRunning = extractionState is ExtractionUiState.Running

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "AutoCrackApp",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Phase 2 · APK Discovery & Extraction",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "列出当前 Android 用户已安装的应用，并把目标应用的 base.apk 与全部 Split APK 复制到 AutoCrackApp 私有工作目录。目标 APK 不会被加载或执行。",
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        item { DeviceCard() }

        item {
            val status = rootStatus
            if (status == null) {
                LoadingCard("正在检测 Root / KernelSU")
            } else {
                RootStatusCard(status)
            }
        }

        item {
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { refreshKey += 1 },
                enabled = rootStatus != null && !extractionRunning,
            ) {
                Text("重新检测并刷新应用列表")
            }
        }

        when (val state = extractionState) {
            ExtractionUiState.Idle -> Unit
            is ExtractionUiState.Running -> item {
                InfoCard("正在提取 APK") {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(state.packageName, fontWeight = FontWeight.SemiBold)
                    Text("正在读取 APK 路径、复制文件并计算 SHA-256，请不要关闭应用。")
                }
            }

            is ExtractionUiState.Success -> item {
                ExtractionReportCard(state.report)
            }

            is ExtractionUiState.Error -> item {
                InfoCard("提取失败") {
                    Text(state.packageName, fontWeight = FontWeight.SemiBold)
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        when (val state = appListState) {
            AppListUiState.Loading -> item {
                LoadingCard("正在读取当前用户的已安装应用")
            }

            is AppListUiState.Error -> item {
                InfoCard("应用列表不可用") {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                }
            }

            is AppListUiState.Ready -> {
                item {
                    InfoCard("已安装应用") {
                        InfoRow("总数", state.apps.size.toString())
                        InfoRow("当前筛选结果", filteredApps.size.toString())
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            label = { Text("搜索包名或 APK 路径") },
                            singleLine = true,
                        )
                    }
                }

                items(
                    items = filteredApps,
                    key = InstalledApp::packageName,
                ) { app ->
                    InstalledAppCard(
                        app = app,
                        extractionRunning = extractionRunning,
                        onExtract = {
                            val status = rootStatus ?: return@InstalledAppCard
                            extractionState = ExtractionUiState.Running(app.packageName)
                            scope.launch {
                                extractionState = try {
                                    ExtractionUiState.Success(
                                        repository.extractPackage(status, app.packageName),
                                    )
                                } catch (exception: Exception) {
                                    ExtractionUiState.Error(
                                        packageName = app.packageName,
                                        message = exception.message ?: "提取时发生未知错误",
                                    )
                                }
                            }
                        },
                    )
                }

                if (filteredApps.isEmpty()) {
                    item {
                        InfoCard("没有匹配结果") {
                            Text("请修改搜索条件后重试。")
                        }
                    }
                }
            }
        }

        item {
            Text(
                text = "工作目录位于应用私有 files/workspaces 下。下一阶段将在本地读取这些副本并生成 Manifest、签名、DEX、资源和 SO 基础清单。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun InstalledAppCard(
    app: InstalledApp,
    extractionRunning: Boolean,
    onExtract: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = if (app.kind == InstalledAppKind.SYSTEM) "系统路径" else "用户路径",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "UID ${app.uid ?: "未知"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = app.primaryApkPath ?: "pm 未返回主 APK 路径",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onExtract,
                enabled = !extractionRunning,
            ) {
                Text("提取 Base / Split APK")
            }
        }
    }
}

@Composable
private fun ExtractionReportCard(report: ExtractionReport) {
    val context = LocalContext.current
    InfoCard("提取完成") {
        InfoRow("包名", report.packageName)
        InfoRow("工作目录", report.workspacePath)
        InfoRow("文件数量", report.artifacts.size.toString())
        InfoRow("Split 数量", report.splitCount.toString())
        InfoRow("总大小", Formatter.formatShortFileSize(context, report.totalBytes))

        report.artifacts.forEach { artifact ->
            HorizontalDivider()
            Text(
                text = if (artifact.kind == ApkArtifactKind.BASE) "Base APK" else "Split APK",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            InfoRow("文件", artifact.fileName)
            InfoRow("大小", Formatter.formatShortFileSize(context, artifact.sizeBytes))
            InfoRow("SHA-256", artifact.sha256)
        }
    }
}

@Composable
private fun DeviceCard() {
    InfoCard("设备环境") {
        InfoRow("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        InfoRow("设备", "${Build.MANUFACTURER} ${Build.MODEL}")
        InfoRow("ABI", Build.SUPPORTED_ABIS.joinToString())
    }
}

@Composable
private fun LoadingCard(message: String) {
    InfoCard("运行状态") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator()
            Column(modifier = Modifier.weight(1f)) {
                Text(message, fontWeight = FontWeight.SemiBold)
                Text("首次操作时 KernelSU 可能弹出授权窗口。")
            }
        }
    }
}

@Composable
private fun RootStatusCard(status: RootStatus) {
    InfoCard("Root 状态") {
        InfoRow("访问状态", status.accessState.displayName())
        InfoRow("Root 管理器", status.provider.displayName())
        InfoRow("su 路径", status.suPath ?: "未找到")
        InfoRow(
            "UID / GID",
            status.identity?.let { "${it.uid ?: "?"} / ${it.gid ?: "?"}" } ?: "未知",
        )
        InfoRow("SELinux", status.identity?.selinuxContext ?: "未知")
        status.diagnostic?.let { diagnostic ->
            Text(diagnostic, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun InfoCard(
    title: String,
    content: @Composable () -> Unit,
) {
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

@Composable
private fun InfoRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun RootAccessState.displayName(): String = when (this) {
    RootAccessState.NOT_AVAILABLE -> "未发现 Root"
    RootAccessState.PERMISSION_REQUIRED -> "等待授权或授权超时"
    RootAccessState.GRANTED -> "已授权"
    RootAccessState.DENIED -> "已拒绝"
    RootAccessState.ERROR -> "检测错误"
}

private fun RootProvider.displayName(): String = when (this) {
    RootProvider.KERNEL_SU -> "KernelSU"
    RootProvider.OTHER -> "其他 su 实现"
    RootProvider.UNKNOWN -> "未知"
}

private fun previewRootStatus(): RootStatus = RootStatus(
    accessState = RootAccessState.GRANTED,
    provider = RootProvider.KERNEL_SU,
    suPath = "/system/bin/su",
    versionName = "3.2.5:KernelSU",
    versionCode = "32525",
    identity = UnixIdentity(uid = 0, gid = 0, selinuxContext = "u:r:ksu:s0"),
    evidence = listOf("KernelSU preview"),
    diagnostic = null,
)

private fun previewApps(): List<InstalledApp> = listOf(
    InstalledApp(
        packageName = "com.example.userapp",
        primaryApkPath = "/data/app/~~example/base.apk",
        uid = 10234,
        kind = InstalledAppKind.USER,
    ),
    InstalledApp(
        packageName = "com.android.settings",
        primaryApkPath = "/system_ext/priv-app/Settings/Settings.apk",
        uid = 1000,
        kind = InstalledAppKind.SYSTEM,
    ),
)
