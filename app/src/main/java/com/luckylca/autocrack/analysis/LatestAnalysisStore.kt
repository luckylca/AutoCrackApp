package com.luckylca.autocrack.analysis

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Keeps only the latest completed analysis in memory so the UI can build a
 * copyable device-test report. APK contents and API keys are never stored here.
 */
object LatestAnalysisStore {
    private val mutableLatestReport = MutableStateFlow<StaticAnalysisReport?>(null)

    val latestReport: StateFlow<StaticAnalysisReport?> = mutableLatestReport.asStateFlow()

    fun publish(report: StaticAnalysisReport) {
        mutableLatestReport.value = report
    }

    fun clear() {
        mutableLatestReport.value = null
    }
}
