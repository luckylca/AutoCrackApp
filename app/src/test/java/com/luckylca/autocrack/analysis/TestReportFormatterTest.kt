package com.luckylca.autocrack.analysis

import org.junit.Assert.assertTrue
import org.junit.Test

class TestReportFormatterTest {
    @Test
    fun format_includesAutomaticResultsManualFeedbackAndNativeDiagnostics() {
        val text = TestReportFormatter.format(
            report = sampleReport(),
            environment = TestReportEnvironment(
                device = "OnePlus PJZ110",
                androidVersion = "16 (API 36)",
                supportedAbis = "arm64-v8a",
                appVersion = "0.4.0-phase4",
            ),
            feedback = TestFeedback(
                stability = ManualTestStatus.PASSED,
                resultAccuracy = ManualTestStatus.FAILED,
                notes = "一个 SO 看起来像加密数据",
            ),
        )

        assertTrue(text.contains("AutoCrackApp 真机测试结果"))
        assertTrue(text.contains("目标包名：com.example.target"))
        assertTrue(text.contains("DEX 数量：1"))
        assertTrue(text.contains("SO 数量：1"))
        assertTrue(text.contains("header=6E 6F 74 2D 65 6C 66"))
        assertTrue(text.contains("卡死、闪退、ANR 或 Root 异常：正常"))
        assertTrue(text.contains("页面结果是否符合实际表现：有问题"))
        assertTrue(text.contains("一个 SO 看起来像加密数据"))
    }

    private fun sampleReport(): StaticAnalysisReport = StaticAnalysisReport(
        packageName = "com.example.target",
        baseApkFileName = "base.apk",
        manifest = ManifestSummary(
            parsed = true,
            diagnostic = null,
            manifestPackageName = "com.example.target",
            versionName = "1.0",
            versionCode = 1,
            minSdk = 26,
            targetSdk = 36,
            compileSdk = 36,
            debuggable = false,
            allowBackup = false,
            usesCleartextTraffic = false,
            extractNativeLibs = true,
        ),
        signing = SigningSummary(
            currentSignerSha256 = listOf("AA:BB"),
            signingHistorySha256 = listOf("AA:BB"),
            diagnostic = null,
        ),
        permissions = PermissionSummary(emptyList(), emptyList()),
        components = ComponentSummary(
            activities = emptyComponentSummary(),
            services = emptyComponentSummary(),
            receivers = emptyComponentSummary(),
            providers = emptyComponentSummary(),
        ),
        archives = listOf(
            ApkArchiveSummary(
                artifactFileName = "base.apk",
                artifactSha256 = "sha256",
                entryCount = 3,
                uncompressedBytes = 100,
                compressedBytes = 80,
                manifestEntryPresent = true,
                resourcesArscPresent = true,
                resourceEntryCount = 0,
                assetEntryCount = 0,
                metaInfEntryCount = 0,
                signingEntryCount = 0,
                nestedApkEntryCount = 0,
                dexFiles = listOf(
                    DexFileSummary("classes.dex", 8, 8, "035", true),
                ),
                nativeLibraries = listOf(
                    NativeLibrarySummary(
                        entryName = "lib/arm64-v8a/libpacked.so",
                        abi = "arm64-v8a",
                        fileName = "libpacked.so",
                        sizeBytes = 7,
                        compressedSizeBytes = 7,
                        elfClass = null,
                        machine = null,
                        validElfMagic = false,
                        headerHex = "6E 6F 74 2D 65 6C 66",
                        diagnostic = "头部不是 ELF 魔数",
                    ),
                ),
                warnings = listOf("SO 诊断"),
            ),
        ),
        reportFilePath = "/data/user/0/app/files/report.json",
        startedAtEpochMillis = 1_000,
        completedAtEpochMillis = 1_250,
    )

    private fun emptyComponentSummary(): ComponentKindSummary = ComponentKindSummary(
        total = 0,
        exported = 0,
        names = emptyList(),
        exportedNames = emptyList(),
    )
}
