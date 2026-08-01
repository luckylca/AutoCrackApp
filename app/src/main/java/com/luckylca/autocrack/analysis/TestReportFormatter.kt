package com.luckylca.autocrack.analysis

enum class ManualTestStatus(val displayName: String) {
    NOT_TESTED("未确认"),
    PASSED("正常"),
    FAILED("有问题"),
}

data class TestReportEnvironment(
    val device: String,
    val androidVersion: String,
    val supportedAbis: String,
    val appVersion: String,
)

data class TestFeedback(
    val stability: ManualTestStatus = ManualTestStatus.NOT_TESTED,
    val resultAccuracy: ManualTestStatus = ManualTestStatus.NOT_TESTED,
    val notes: String = "",
)

object TestReportFormatter {
    fun format(
        report: StaticAnalysisReport,
        environment: TestReportEnvironment,
        feedback: TestFeedback,
    ): String {
        val durationMillis = (report.completedAtEpochMillis - report.startedAtEpochMillis)
            .coerceAtLeast(0L)
        val nativeDiagnostics = report.archives
            .flatMap(ApkArchiveSummary::nativeLibraries)
            .filter { library -> library.diagnostic != null }

        return buildString {
            appendLine("AutoCrackApp 真机测试结果")
            appendLine("版本：${environment.appVersion}")
            appendLine("设备：${environment.device}")
            appendLine("Android：${environment.androidVersion}")
            appendLine("ABI：${environment.supportedAbis}")
            appendLine("目标包名：${report.packageName}")
            appendLine()

            appendLine("1. APK 提取与静态分析：通过")
            appendLine("   Base APK：${report.baseApkFileName}")
            appendLine("   APK 数量：${report.archives.size}")
            appendLine("   耗时：${durationMillis} ms")
            appendLine()

            appendLine("2. Manifest 与签名：${manifestAndSigningStatus(report)}")
            appendLine("   Manifest 包名：${report.manifest.manifestPackageName ?: "未知"}")
            appendLine("   版本：${report.manifest.versionName ?: "未知"}")
            appendLine("   targetSdk：${report.manifest.targetSdk ?: "未知"}")
            appendLine("   当前签名证书：${report.signing.currentSignerSha256.size}")
            appendLine()

            appendLine("3. DEX / SO / 结构盘点：完成")
            appendLine("   DEX 数量：${report.dexFileCount}")
            appendLine("   SO 数量：${report.nativeLibraryCount}")
            appendLine("   ZIP 条目：${report.totalZipEntries}")
            appendLine("   总警告：${report.warnings.size}")
            appendLine("   SO 诊断项：${nativeDiagnostics.size}")
            nativeDiagnostics.take(MAX_NATIVE_DIAGNOSTICS).forEach { library ->
                appendLine(
                    "   - ${library.entryName} | ${library.sizeBytes} B | " +
                        "header=${library.headerHex.ifBlank { "<empty>" }} | " +
                        (library.diagnostic ?: "无"),
                )
            }
            if (nativeDiagnostics.size > MAX_NATIVE_DIAGNOSTICS) {
                appendLine("   - 其余 ${nativeDiagnostics.size - MAX_NATIVE_DIAGNOSTICS} 项见 analysis-report.json")
            }
            appendLine()

            appendLine("4. 卡死、闪退、ANR 或 Root 异常：${feedback.stability.displayName}")
            appendLine("5. 页面结果是否符合实际表现：${feedback.resultAccuracy.displayName}")
            appendLine()
            appendLine("补充说明：${feedback.notes.trim().ifBlank { "无" }}")
            appendLine("报告路径：${report.reportFilePath}")
        }.trimEnd()
    }

    private fun manifestAndSigningStatus(report: StaticAnalysisReport): String = when {
        !report.manifest.parsed -> "失败（Manifest 未解析）"
        report.signing.currentSignerSha256.isEmpty() -> "部分完成（未读取到当前签名证书）"
        else -> "通过"
    }

    private const val MAX_NATIVE_DIAGNOSTICS = 20
}
