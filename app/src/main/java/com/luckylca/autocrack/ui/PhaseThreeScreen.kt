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
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.luckylca.autocrack.analysis.ApkArchiveSummary
import com.luckylca.autocrack.analysis.ApkStaticAnalyzer
import com.luckylca.autocrack.analysis.ComponentKindSummary
import com.luckylca.autocrack.analysis.StaticAnalysisReport
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

private sealed interface PhaseThreeAppListState {
    data object Loading : PhaseThreeAppListState
    data class Ready(val apps: List<InstalledApp>) : PhaseThreeAppListState
    data class Error(val message: String) : PhaseThreeAppListState
}

private sealed interface AnalysisOperationState {
    data object Idle : AnalysisOperationState
    data class Running(val packageName: String, val stage: String) : AnalysisOperationState
    data class Success(
        val extraction: ExtractionReport,
        val analysis: StaticAnalysisReport,
    ) : AnalysisOperationState

    data class Error(val packageName: String, val message: String) : AnalysisOperationState
}

@Composable
fun PhaseThreeScreen() {
    val context = LocalContext.current.applicationContext
    val inspectionMode = LocalInspectionMode.current
    val runner = remember { ProcessRootCommandRunner() }
    val detector = remember(runner) { RootDetector(runner) }
    val repository = remember(context, runner) { PackageRepository(context, runner) }
    val analyzer = remember(context) { ApkStaticAnalyzer(context) }
    val scope = rememberCoroutineScope()

    var refreshKey by remember { mutableIntStateOf(0) }
    var rootStatus by remember { mutableStateOf<RootStatus?>(null) }
    var appListState by remember {
        mutableStateOf<PhaseThreeAppListState>(PhaseThreeAppListState.Loading)
    }
    var operationState by remember { mutableStateOf<AnalysisOperationState>(AnalysisOperationState.Idle) }
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(refreshKey, inspectionMode) {
        rootStatus = null
        appListState = PhaseThreeAppListState.Loading
        operationState = AnalysisOperationState.Idle

        val status = if (inspectionMode) phaseThreePreviewRootStatus() else detector.inspect()
        rootStatus = status
        appListState = if (!status.isRootGranted) {
            PhaseThreeAppListState.Error(status.diagnostic ?: "Root 未授权，无法读取应用列表")
        } else {
            try {
                val apps = if (inspectionMode) phaseThreePreviewApps() else repository.listInstalledApps(status)
                PhaseThreeAppListState.Ready(apps)
            } catch (exception: Exception) {
                PhaseThreeAppListState.Error(exception.message ?: "读取应用列表时发生未知错误")
            }
        }
    }

    val allApps = (appListState as? PhaseThreeAppListState.Ready)?.apps.orEmpty()
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
    val operationRunning = operationState is AnalysisOperationState.Running

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
                text = "Phase 3 · Local Static Inventory",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "先提取目标 Base / Split APK，再在本机读取 Manifest、签名证书、DEX、资源和 SO 清单。分析过程不会加载 DEX、不会执行 SO，也不会把文件发送到外部 API。",
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        item { PhaseThreeDeviceCard() }

        item {
            val status = rootStatus
            if (status == null) {
                PhaseThreeLoadingCard("正在检测 Root / KernelSU")
            } else {
                PhaseThreeRootStatusCard(status)
            }
        }

        item {
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { refreshKey += 1 },
                enabled = rootStatus != null && !operationRunning,
            ) {
                Text("重新检测并刷新应用列表")
            }
        }

        when (val state = operationState) {
            AnalysisOperationState.Idle -> Unit
            is AnalysisOperationState.Running -> item {
                PhaseThreeInfoCard("正在处理") {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    PhaseThreeInfoRow("目标包名", state.packageName)
                    PhaseThreeInfoRow("当前阶段", state.stage)
                    Text("大体积 APK 或包含大量 SO 时可能需要更长时间，请不要关闭应用。")
                }
            }

            is AnalysisOperationState.Success -> {
                item { PhaseThreeExtractionCard(state.extraction) }
                item { ManifestAnalysisCard(state.analysis) }
                item { SigningAnalysisCard(state.analysis) }
                item { PermissionAnalysisCard(state.analysis) }
                item { ComponentAnalysisCard(state.analysis) }
                state.analysis.archives.forEach { archive ->
                    item(key = "archive-${archive.artifactFileName}") {
                        ArchiveAnalysisCard(archive)
                    }
                }
                item { AnalysisOutputCard(state.analysis) }
            }

            is AnalysisOperationState.Error -> item {
                PhaseThreeInfoCard("处理失败") {
                    PhaseThreeInfoRow("目标包名", state.packageName)
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        when (val state = appListState) {
            PhaseThreeAppListState.Loading -> item {
                PhaseThreeLoadingCard("正在读取当前用户的已安装应用")
            }

            is PhaseThreeAppListState.Error -> item {
                PhaseThreeInfoCard("应用列表不可用") {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                }
            }

            is PhaseThreeAppListState.Ready -> {
                item {
                    PhaseThreeInfoCard("已安装应用") {
                        PhaseThreeInfoRow("总数", state.apps.size.toString())
                        PhaseThreeInfoRow("当前筛选结果", filteredApps.size.toString())
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
                    PhaseThreeInstalledAppCard(
                        app = app,
                        operationRunning = operationRunning,
                        onAnalyze = {
                            val status = rootStatus ?: return@PhaseThreeInstalledAppCard
                            if (inspectionMode) return@PhaseThreeInstalledAppCard
                            scope.launch {
                                operationState = AnalysisOperationState.Running(
                                    packageName = app.packageName,
                                    stage = "提取 Base / Split APK 并校验 SHA-256",
                                )
                                try {
                                    val extraction = repository.extractPackage(status, app.packageName)
                                    operationState = AnalysisOperationState.Running(
                                        packageName = app.packageName,
                                        stage = "解析 Manifest、签名、DEX、资源与 SO 清单",
                                    )
                                    val analysis = analyzer.analyze(extraction)
                                    operationState = AnalysisOperationState.Success(extraction, analysis)
                                } catch (exception: Exception) {
                                    operationState = AnalysisOperationState.Error(
                                        packageName = app.packageName,
                                        message = exception.message ?: "静态盘点时发生未知错误",
                                    )
                                }
                            }
                        },
                    )
                }

                if (filteredApps.isEmpty()) {
                    item {
                        PhaseThreeInfoCard("没有匹配结果") {
                            Text("请修改搜索条件后重试。")
                        }
                    }
                }
            }
        }

        item {
            Text(
                text = "结构化结果会保存为工作目录中的 analysis-report.json。下一阶段将基于该报告解析 DEX 类与方法索引、字符串和基础调用关系。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun PhaseThreeInstalledAppCard(
    app: InstalledApp,
    operationRunning: Boolean,
    onAnalyze: () -> Unit,
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
                onClick = onAnalyze,
                enabled = !operationRunning,
            ) {
                Text("提取并静态盘点")
            }
        }
    }
}

@Composable
private fun PhaseThreeExtractionCard(report: ExtractionReport) {
    val context = LocalContext.current
    PhaseThreeInfoCard("APK 提取完成") {
        PhaseThreeInfoRow("包名", report.packageName)
        PhaseThreeInfoRow("工作目录", report.workspacePath)
        PhaseThreeInfoRow("APK 文件数量", report.artifacts.size.toString())
        PhaseThreeInfoRow("Split 数量", report.splitCount.toString())
        PhaseThreeInfoRow("总大小", Formatter.formatShortFileSize(context, report.totalBytes))
        report.artifacts.forEach { artifact ->
            HorizontalDivider()
            Text(
                text = if (artifact.kind == ApkArtifactKind.BASE) "Base APK" else "Split APK",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            PhaseThreeInfoRow("文件", artifact.fileName)
            PhaseThreeInfoRow("大小", Formatter.formatShortFileSize(context, artifact.sizeBytes))
            PhaseThreeInfoRow("SHA-256", artifact.sha256)
        }
    }
}

@Composable
private fun ManifestAnalysisCard(report: StaticAnalysisReport) {
    val manifest = report.manifest
    PhaseThreeInfoCard("Manifest 基础信息") {
        PhaseThreeInfoRow("解析状态", if (manifest.parsed) "成功" else "失败")
        manifest.diagnostic?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        PhaseThreeInfoRow("Manifest 包名", manifest.manifestPackageName ?: "未知")
        PhaseThreeInfoRow("版本名称", manifest.versionName ?: "未知")
        PhaseThreeInfoRow("版本代码", manifest.versionCode?.toString() ?: "未知")
        PhaseThreeInfoRow("minSdk", manifest.minSdk?.toString() ?: "未知")
        PhaseThreeInfoRow("targetSdk", manifest.targetSdk?.toString() ?: "未知")
        PhaseThreeInfoRow("compileSdk", manifest.compileSdk?.toString() ?: "系统未提供")
        PhaseThreeInfoRow("可调试 debuggable", manifest.debuggable.toDisplayText())
        PhaseThreeInfoRow("允许备份 allowBackup", manifest.allowBackup.toDisplayText())
        PhaseThreeInfoRow("允许明文流量", manifest.usesCleartextTraffic.toDisplayText())
        PhaseThreeInfoRow("安装时提取 SO", manifest.extractNativeLibs.toDisplayText())
    }
}

@Composable
private fun SigningAnalysisCard(report: StaticAnalysisReport) {
    val signing = report.signing
    PhaseThreeInfoCard("APK 签名证书") {
        PhaseThreeInfoRow("当前签名者数量", signing.currentSignerSha256.size.toString())
        signing.currentSignerSha256.forEachIndexed { index, fingerprint ->
            PhaseThreeInfoRow("当前证书 ${index + 1} · SHA-256", fingerprint)
        }
        PhaseThreeInfoRow("历史证书数量", signing.signingHistorySha256.size.toString())
        signing.diagnostic?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun PermissionAnalysisCard(report: StaticAnalysisReport) {
    PhaseThreeInfoCard("权限清单") {
        PhaseThreeInfoRow("请求权限数量", report.permissions.requested.size.toString())
        CompactStringList("请求权限", report.permissions.requested, MAX_VISIBLE_PERMISSIONS)
        PhaseThreeInfoRow("自定义权限数量", report.permissions.declared.size.toString())
        CompactStringList("自定义权限", report.permissions.declared, MAX_VISIBLE_PERMISSIONS)
    }
}

@Composable
private fun ComponentAnalysisCard(report: StaticAnalysisReport) {
    PhaseThreeInfoCard("组件与导出面") {
        ComponentKindRows("Activity", report.components.activities)
        HorizontalDivider()
        ComponentKindRows("Service", report.components.services)
        HorizontalDivider()
        ComponentKindRows("Receiver", report.components.receivers)
        HorizontalDivider()
        ComponentKindRows("Provider", report.components.providers)
    }
}

@Composable
private fun ComponentKindRows(label: String, summary: ComponentKindSummary) {
    PhaseThreeInfoRow("$label 总数 / exported", "${summary.total} / ${summary.exported}")
    CompactStringList("导出的 $label", summary.exportedNames, MAX_VISIBLE_COMPONENTS)
}

@Composable
private fun ArchiveAnalysisCard(archive: ApkArchiveSummary) {
    val context = LocalContext.current
    PhaseThreeInfoCard("APK 归档：${archive.artifactFileName}") {
        PhaseThreeInfoRow("ZIP 条目数量", archive.entryCount.toString())
        PhaseThreeInfoRow(
            "压缩大小 / 解压大小",
            "${Formatter.formatShortFileSize(context, archive.compressedBytes)} / " +
                Formatter.formatShortFileSize(context, archive.uncompressedBytes),
        )
        PhaseThreeInfoRow("AndroidManifest.xml", archive.manifestEntryPresent.toDisplayText())
        PhaseThreeInfoRow("resources.arsc", archive.resourcesArscPresent.toDisplayText())
        PhaseThreeInfoRow("res / assets 条目", "${archive.resourceEntryCount} / ${archive.assetEntryCount}")
        PhaseThreeInfoRow("META-INF / 签名条目", "${archive.metaInfEntryCount} / ${archive.signingEntryCount}")
        PhaseThreeInfoRow("内嵌 APK 数量", archive.nestedApkEntryCount.toString())
        PhaseThreeInfoRow("DEX 数量", archive.dexFiles.size.toString())
        archive.dexFiles.forEach { dex ->
            Text(
                text = "${dex.entryName} · DEX ${dex.dexVersion ?: "未知"} · " +
                    Formatter.formatShortFileSize(context, dex.sizeBytes),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        PhaseThreeInfoRow("SO 数量", archive.nativeLibraries.size.toString())
        PhaseThreeInfoRow("ABI", archive.abis.ifEmpty { listOf("无") }.joinToString())
        archive.nativeLibraries.take(MAX_VISIBLE_NATIVE_LIBRARIES).forEach { library ->
            Text(
                text = "${library.abi}/${library.fileName} · " +
                    "${library.elfClass ?: "未知"} ${library.machine ?: "未知"} · " +
                    Formatter.formatShortFileSize(context, library.sizeBytes),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (archive.nativeLibraries.size > MAX_VISIBLE_NATIVE_LIBRARIES) {
            Text("另有 ${archive.nativeLibraries.size - MAX_VISIBLE_NATIVE_LIBRARIES} 个 SO 已写入 JSON 报告。")
        }
        if (archive.warnings.isNotEmpty()) {
            HorizontalDivider()
            Text("归档警告", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
            archive.warnings.forEach { warning ->
                Text("• $warning", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun AnalysisOutputCard(report: StaticAnalysisReport) {
    PhaseThreeInfoCard("静态盘点完成") {
        PhaseThreeInfoRow("APK 归档数量", report.archives.size.toString())
        PhaseThreeInfoRow("ZIP 总条目", report.totalZipEntries.toString())
        PhaseThreeInfoRow("DEX 总数", report.dexFileCount.toString())
        PhaseThreeInfoRow("SO 总数", report.nativeLibraryCount.toString())
        PhaseThreeInfoRow("警告数量", report.warnings.size.toString())
        PhaseThreeInfoRow("结构化报告", report.reportFilePath)
    }
}

@Composable
private fun CompactStringList(title: String, values: List<String>, maxVisible: Int) {
    if (values.isEmpty()) {
        Text("$title：无", style = MaterialTheme.typography.bodySmall)
        return
    }
    values.take(maxVisible).forEach { value ->
        Text("• $value", style = MaterialTheme.typography.bodySmall)
    }
    if (values.size > maxVisible) {
        Text("另有 ${values.size - maxVisible} 项已写入 JSON 报告。")
    }
}

@Composable
private fun PhaseThreeDeviceCard() {
    PhaseThreeInfoCard("设备环境") {
        PhaseThreeInfoRow("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        PhaseThreeInfoRow("设备", "${Build.MANUFACTURER} ${Build.MODEL}")
        PhaseThreeInfoRow("ABI", Build.SUPPORTED_ABIS.joinToString())
    }
}

@Composable
private fun PhaseThreeLoadingCard(message: String) {
    PhaseThreeInfoCard("运行状态") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator()
            Column {
                Text(message, fontWeight = FontWeight.SemiBold)
                Text("首次操作时 KernelSU 可能弹出授权窗口。")
            }
        }
    }
}

@Composable
private fun PhaseThreeRootStatusCard(status: RootStatus) {
    PhaseThreeInfoCard("Root 状态") {
        PhaseThreeInfoRow("访问状态", status.accessState.phaseThreeDisplayName())
        PhaseThreeInfoRow("Root 管理器", status.provider.phaseThreeDisplayName())
        PhaseThreeInfoRow("su 路径", status.suPath ?: "未找到")
        PhaseThreeInfoRow(
            "UID / GID",
            status.identity?.let { "${it.uid ?: "?"} / ${it.gid ?: "?"}" } ?: "未知",
        )
        PhaseThreeInfoRow("SELinux", status.identity?.selinuxContext ?: "未知")
        status.diagnostic?.let { diagnostic ->
            Text(diagnostic, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun PhaseThreeInfoCard(
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
private fun PhaseThreeInfoRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun Boolean?.toDisplayText(): String = when (this) {
    true -> "是"
    false -> "否"
    null -> "未知"
}

private fun RootAccessState.phaseThreeDisplayName(): String = when (this) {
    RootAccessState.NOT_AVAILABLE -> "未发现 Root"
    RootAccessState.PERMISSION_REQUIRED -> "等待授权或授权超时"
    RootAccessState.GRANTED -> "已授权"
    RootAccessState.DENIED -> "已拒绝"
    RootAccessState.ERROR -> "检测错误"
}

private fun RootProvider.phaseThreeDisplayName(): String = when (this) {
    RootProvider.KERNEL_SU -> "KernelSU"
    RootProvider.OTHER -> "其他 su 实现"
    RootProvider.UNKNOWN -> "未知"
}

private fun phaseThreePreviewRootStatus(): RootStatus = RootStatus(
    accessState = RootAccessState.GRANTED,
    provider = RootProvider.KERNEL_SU,
    suPath = "/system/bin/su",
    versionName = "3.2.5:KernelSU",
    versionCode = "32525",
    identity = UnixIdentity(uid = 0, gid = 0, selinuxContext = "u:r:ksu:s0"),
    evidence = listOf("KernelSU preview"),
    diagnostic = null,
)

private fun phaseThreePreviewApps(): List<InstalledApp> = listOf(
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

private const val MAX_VISIBLE_PERMISSIONS = 20
private const val MAX_VISIBLE_COMPONENTS = 12
private const val MAX_VISIBLE_NATIVE_LIBRARIES = 24
