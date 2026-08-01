package com.luckylca.autocrack.agent

import com.luckylca.autocrack.analysis.StaticAnalysisReport
import com.luckylca.autocrack.apk.ExtractionReport
import com.luckylca.autocrack.dex.DexIndexSummary
import com.luckylca.autocrack.dex.LocalAgentResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class PhaseFiveDiagnosticSeverity {
    INFO,
    WARNING,
    ERROR,
}

data class PhaseFiveDiagnosticEvent(
    val timestampEpochMillis: Long,
    val severity: PhaseFiveDiagnosticSeverity,
    val stage: String,
    val message: String,
    val exceptionType: String? = null,
    val stackTrace: String? = null,
)

fun phaseFiveDiagnosticEvent(
    stage: String,
    message: String,
    severity: PhaseFiveDiagnosticSeverity = PhaseFiveDiagnosticSeverity.INFO,
    throwable: Throwable? = null,
): PhaseFiveDiagnosticEvent = PhaseFiveDiagnosticEvent(
    timestampEpochMillis = System.currentTimeMillis(),
    severity = severity,
    stage = stage,
    message = message,
    exceptionType = throwable?.javaClass?.name,
    stackTrace = throwable?.stackTraceToString()?.take(MAX_DIAGNOSTIC_STACK_CHARS),
)

data class PhaseFiveDiagnosticSnapshot(
    val versionName: String,
    val device: String,
    val androidVersion: String,
    val abi: String,
    val selectedPackageName: String?,
    val rootStatus: String,
    val appListStatus: String,
    val workspaceStatus: String,
    val queryStatus: String,
    val extraction: ExtractionReport?,
    val staticReport: StaticAnalysisReport?,
    val dexIndex: DexIndexSummary?,
    val localResult: LocalAgentResult?,
    val llmAnswer: LlmAgentAnswer?,
    val events: List<PhaseFiveDiagnosticEvent>,
    val note: String,
)

object PhaseFiveDiagnosticReportFormatter {
    fun format(snapshot: PhaseFiveDiagnosticSnapshot): String = buildString {
        appendLine("AutoCrackApp Phase 5 完整诊断报告")
        appendLine("版本：${snapshot.versionName}")
        appendLine("设备：${snapshot.device}")
        appendLine("Android：${snapshot.androidVersion}")
        appendLine("ABI：${snapshot.abi}")
        appendLine("目标包名：${snapshot.selectedPackageName ?: "未选择"}")
        appendLine()

        appendLine("1. 当前运行状态")
        appendLine("   Root：${snapshot.rootStatus}")
        appendLine("   应用列表：${snapshot.appListStatus}")
        appendLine("   工作区：${snapshot.workspaceStatus}")
        appendLine("   Agent：${snapshot.queryStatus}")
        appendLine()

        val extraction = snapshot.extraction
        val staticReport = snapshot.staticReport
        val dexIndex = snapshot.dexIndex
        if (extraction == null || staticReport == null || dexIndex == null) {
            appendLine("2. 工作区结果：未完整建立")
        } else {
            val staticDurationMillis = (
                staticReport.completedAtEpochMillis - staticReport.startedAtEpochMillis
                ).coerceAtLeast(0L)
            appendLine("2. 工作区结果：已建立")
            appendLine("   APK 数量：${extraction.artifacts.size}")
            appendLine("   静态分析耗时：$staticDurationMillis ms")
            appendLine("   Manifest：${if (staticReport.manifest.parsed) "成功" else "失败"}")
            appendLine("   静态 DEX 数量：${staticReport.dexFileCount}")
            appendLine("   SO 数量：${staticReport.nativeLibraryCount}")
            appendLine("   索引 DEX 条目：${dexIndex.dexEntryCount}")
            appendLine("   类：${dexIndex.classCount}")
            appendLine("   方法：${dexIndex.methodCount}")
            appendLine("   字段：${dexIndex.fieldCount}")
            appendLine("   字符串：${dexIndex.stringCount}")
            appendLine("   跳过字符串：${dexIndex.skippedStringCount}")
            appendLine("   索引大小：${dexIndex.indexBytes} B")
            appendLine("   索引耗时：${dexIndex.durationMillis} ms")
            appendLine("   静态报告：${staticReport.reportFilePath}")
            appendLine("   DEX 索引：${dexIndex.databasePath}")
        }
        appendLine()

        val local = snapshot.localResult
        appendLine("3. 本地分析：${if (local == null) "未成功完成" else "完成"}")
        if (local != null) {
            appendLine("   问题：${local.question}")
            appendLine("   搜索词：${local.expandedTerms.joinToString()}")
            appendLine("   证据数量：${local.evidence.size}")
            appendLine("   检索耗时：${local.durationMillis} ms")
            appendLine("   结果文件：${local.resultFilePath}")
            local.evidence.take(10).forEach { item ->
                appendLine("   - [${item.kind.name}] ${item.symbol} @ ${item.dexEntry}")
            }
        }
        appendLine()

        val llm = snapshot.llmAnswer
        appendLine("4. 外部模型：${if (llm == null) "未成功完成或未配置" else "完成"}")
        if (llm != null) {
            appendLine("   模型：${llm.model}")
            appendLine("   主机：${llm.endpointHost}")
            appendLine("   发送证据：${llm.requestEvidenceCount}")
            appendLine("   耗时：${llm.durationMillis} ms")
            appendLine("   答案预览：${llm.content.replace('\n', ' ').take(500)}")
        }
        appendLine()

        appendLine("5. 自动诊断事件：${snapshot.events.size}")
        if (snapshot.events.isEmpty()) {
            appendLine("   无已记录事件")
        } else {
            snapshot.events.forEachIndexed { index, event ->
                appendLine("   ${index + 1}. ${formatTimestamp(event.timestampEpochMillis)} [${event.severity}] ${event.stage}")
                appendLine("      ${event.message}")
                event.exceptionType?.let { appendLine("      异常类型：$it") }
                event.stackTrace?.takeIf(String::isNotBlank)?.let { stack ->
                    appendLine("      堆栈：")
                    stack.lineSequence().forEach { line -> appendLine("        $line") }
                }
            }
        }
        appendLine()
        appendLine("补充说明：${snapshot.note.ifBlank { "无" }}")
    }.trimEnd()

    private fun formatTimestamp(epochMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(epochMillis))
}

private const val MAX_DIAGNOSTIC_STACK_CHARS = 6_000
