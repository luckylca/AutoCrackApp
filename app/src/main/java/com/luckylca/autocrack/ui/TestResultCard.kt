package com.luckylca.autocrack.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.luckylca.autocrack.BuildConfig
import com.luckylca.autocrack.analysis.ManualTestStatus
import com.luckylca.autocrack.analysis.StaticAnalysisReport
import com.luckylca.autocrack.analysis.TestFeedback
import com.luckylca.autocrack.analysis.TestReportEnvironment
import com.luckylca.autocrack.analysis.TestReportFormatter

@Composable
fun TestResultDialog(
    report: StaticAnalysisReport,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var stability by remember(report.completedAtEpochMillis) {
        mutableStateOf(ManualTestStatus.NOT_TESTED)
    }
    var resultAccuracy by remember(report.completedAtEpochMillis) {
        mutableStateOf(ManualTestStatus.NOT_TESTED)
    }
    var notes by remember(report.completedAtEpochMillis) { mutableStateOf("") }

    val environment = remember {
        TestReportEnvironment(
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            supportedAbis = Build.SUPPORTED_ABIS.joinToString(),
            appVersion = BuildConfig.VERSION_NAME,
        )
    }
    val durationMillis = (report.completedAtEpochMillis - report.startedAtEpochMillis)
        .coerceAtLeast(0L)
    val nativeDiagnostics = report.nativeLibraryDiagnostics

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("本阶段真机测试结果")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "测试项和可自动采集的结果已经填好。请确认最后两项，然后点击复制，直接粘贴给我。",
                    style = MaterialTheme.typography.bodyMedium,
                )

                AutomaticTestRow(
                    number = 1,
                    title = "APK 提取与静态分析",
                    result = "通过 · ${report.archives.size} 个 APK · ${durationMillis} ms",
                )
                AutomaticTestRow(
                    number = 2,
                    title = "Manifest 与签名",
                    result = when {
                        !report.manifest.parsed -> "失败 · Manifest 未解析"
                        report.signing.currentSignerSha256.isEmpty() -> "部分完成 · 未读取到当前签名"
                        else -> "通过 · ${report.signing.currentSignerSha256.size} 个当前签名"
                    },
                )
                AutomaticTestRow(
                    number = 3,
                    title = "DEX / SO / 结构盘点",
                    result = "DEX ${report.dexFileCount} · SO ${report.nativeLibraryCount} · " +
                        "警告 ${report.warnings.size} · SO 诊断 ${nativeDiagnostics.size}",
                )

                if (nativeDiagnostics.isNotEmpty()) {
                    HorizontalDivider()
                    Text("SO 诊断预览", fontWeight = FontWeight.SemiBold)
                    nativeDiagnostics.take(MAX_VISIBLE_NATIVE_DIAGNOSTICS).forEach { library ->
                        Text(
                            text = "${library.entryName}\n" +
                                "${library.sizeBytes} B · header=${library.headerHex.ifBlank { "<empty>" }}\n" +
                                (library.diagnostic ?: "无诊断"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (nativeDiagnostics.size > MAX_VISIBLE_NATIVE_DIAGNOSTICS) {
                        Text(
                            text = "其余 ${nativeDiagnostics.size - MAX_VISIBLE_NATIVE_DIAGNOSTICS} 项会包含在复制结果中。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                HorizontalDivider()
                ManualTestRow(
                    number = 4,
                    title = "是否出现卡死、闪退、ANR 或 Root 异常？",
                    selected = stability,
                    onSelected = { stability = it },
                )
                ManualTestRow(
                    number = 5,
                    title = "页面展示结果是否符合实际表现？",
                    selected = resultAccuracy,
                    onSelected = { resultAccuracy = it },
                )

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("补充说明，例如某个 SO 警告或界面问题") },
                    minLines = 3,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val text = TestReportFormatter.format(
                        report = report,
                        environment = environment,
                        feedback = TestFeedback(
                            stability = stability,
                            resultAccuracy = resultAccuracy,
                            notes = notes,
                        ),
                    )
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard.setPrimaryClip(ClipData.newPlainText("AutoCrackApp 真机测试结果", text))
                    Toast.makeText(context, "测试结果已复制，可以直接粘贴发送", Toast.LENGTH_SHORT).show()
                },
            ) {
                Text("复制完整测试结果")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
    )
}

@Composable
private fun AutomaticTestRow(
    number: Int,
    title: String,
    result: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$number. $title", fontWeight = FontWeight.SemiBold)
        Text(
            text = result,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun ManualTestRow(
    number: Int,
    title: String,
    selected: ManualTestStatus,
    onSelected: (ManualTestStatus) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("$number. $title", fontWeight = FontWeight.SemiBold)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ManualTestStatus.entries.forEach { status ->
                FilterChip(
                    selected = selected == status,
                    onClick = { onSelected(status) },
                    label = { Text(status.displayName) },
                )
            }
        }
    }
}

private const val MAX_VISIBLE_NATIVE_DIAGNOSTICS = 5
