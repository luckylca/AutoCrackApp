package com.luckylca.autocrack.tools

data class ElfAnalysisReport(
    val sourceLabel: String,
    val fileSizeBytes: Long,
    val elfClass: String,
    val byteOrder: String,
    val objectType: String,
    val machine: String,
    val entryPoint: Long,
    val buildId: String?,
    val soname: String?,
    val neededLibraries: List<String>,
    val rpath: String?,
    val runpath: String?,
    val sections: List<ElfSectionSummary>,
    val segments: List<ElfSegmentSummary>,
    val importedSymbols: List<String>,
    val exportedSymbols: List<String>,
    val jniSymbols: List<String>,
    val interestingStrings: List<String>,
    val hardening: ElfHardeningSummary,
    val diagnostics: List<String>,
)

data class ElfSectionSummary(
    val index: Int,
    val name: String,
    val type: String,
    val offset: Long,
    val size: Long,
    val flags: Long,
)

data class ElfSegmentSummary(
    val index: Int,
    val type: String,
    val offset: Long,
    val fileSize: Long,
    val memorySize: Long,
    val flags: String,
)

data class ElfHardeningSummary(
    val nx: String,
    val relro: String,
    val bindNow: Boolean,
    val stackCanary: Boolean,
    val fortifiedFunctions: Boolean,
    val stripped: Boolean,
    val positionIndependent: Boolean,
)
