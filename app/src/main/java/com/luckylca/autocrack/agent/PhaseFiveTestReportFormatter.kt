package com.luckylca.autocrack.agent

import com.luckylca.autocrack.analysis.StaticAnalysisReport
import com.luckylca.autocrack.apk.ExtractionReport
import com.luckylca.autocrack.dex.DexIndexSummary
import com.luckylca.autocrack.dex.LocalAgentResult

data class PhaseFiveTestSnapshot(
    val versionName: String,
    val device: String,
    val androidVersion: String,
    val abi: String,
    val extraction: ExtractionReport,
    val staticReport: StaticAnalysisReport,
    val dexIndex: DexIndexSummary,
    val localResult: LocalAgentResult?,
    val llmAnswer: LlmAgentAnswer?,
    val stabilityStatus: String,
    val accuracyStatus: String,
    val note: String,
)

object PhaseFiveTestReportFormatter {
    fun format(snapshot: PhaseFiveTestSnapshot): String = buildString {
        val manifest = snapshot.staticReport.manifest
        val staticDurationMillis = (
            snapshot.staticReport.completedAtEpochMillis -
                snapshot.staticReport.startedAtEpochMillis
            ).coerceAtLeast(0L)

        appendLine("AutoCrackApp Phase 5 真机测试结果")
        appendLine("版本：${snapshot.versionName}")
        appendLine("设备：${snapshot.device}")
        appendLine("Android：${snapshot.androidVersion}")
        appendLine("ABI：${snapshot.abi}")
        appendLine("目标包名：${snapshot.extraction.packageName}")
        appendLine()
        appendLine("1. APK 提取与静态盘点：通过")
        appendLine("   APK 数量：${snapshot.extraction.artifacts.size}")
        appendLine("   静态分析耗时：$staticDurationMillis ms")
        appendLine("   Manifest：${if (manifest.parsed) "成功" else "失败"}")
        appendLine("   DEX：${snapshot.staticReport.dexFileCount}")
        appendLine("   SO：${snapshot.staticReport.nativeLibraryCount}")
        appendLine()
        appendLine("2. DEX 证据索引：完成")
        appendLine("   DEX 条目：${snapshot.dexIndex.dexEntryCount}")
        appendLine("   类：${snapshot.dexIndex.classCount}")
        appendLine("   方法：${snapshot.dexIndex.methodCount}")
        appendLine("   字段：${snapshot.dexIndex.fieldCount}")
        appendLine("   字符串：${snapshot.dexIndex.stringCount}")
        appendLine("   跳过字符串：${snapshot.dexIndex.skippedStringCount}")
        appendLine("   索引大小：${snapshot.dexIndex.indexBytes} B")
        appendLine("   建立耗时：${snapshot.dexIndex.durationMillis} ms")
        appendLine()
        val local = snapshot.localResult
        appendLine("3. 一句话本地分析：${if (local == null) "未测试" else "完成"}")
        if (local != null) {
            appendLine("   问题：${local.question}")
            appendLine("   搜索词：${local.expandedTerms.joinToString()}")
            appendLine("   证据数量：${local.evidence.size}")
            appendLine("   检索耗时：${local.durationMillis} ms")
            local.evidence.take(10).forEach { item ->
                appendLine("   - [${item.kind.name}] ${item.symbol} @ ${item.dexEntry}")
            }
        }
        appendLine()
        val llm = snapshot.llmAnswer
        appendLine("4. 外部模型分析：${if (llm == null) "未测试或未配置" else "完成"}")
        if (llm != null) {
            appendLine("   模型：${llm.model}")
            appendLine("   主机：${llm.endpointHost}")
            appendLine("   发送证据：${llm.requestEvidenceCount}")
            appendLine("   耗时：${llm.durationMillis} ms")
            appendLine("   答案预览：${llm.content.replace('\n', ' ').take(500)}")
        }
        appendLine()
        appendLine("5. 卡死、闪退、ANR、Root 或索引异常：${snapshot.stabilityStatus}")
        appendLine("6. 本地检索与模型回答是否符合实际：${snapshot.accuracyStatus}")
        appendLine()
        appendLine("补充说明：${snapshot.note.ifBlank { "无" }}")
        appendLine("静态报告：${snapshot.staticReport.reportFilePath}")
        appendLine("DEX 索引：${snapshot.dexIndex.databasePath}")
        local?.let { appendLine("本地结果：${it.resultFilePath}") }
    }.trimEnd()
}
