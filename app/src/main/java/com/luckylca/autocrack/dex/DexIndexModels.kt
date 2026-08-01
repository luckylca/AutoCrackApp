package com.luckylca.autocrack.dex

enum class DexEvidenceKind {
    CLASS,
    METHOD,
    FIELD,
    STRING,
}

data class DexIndexSummary(
    val databasePath: String,
    val summaryPath: String,
    val dexEntryCount: Int,
    val classCount: Long,
    val methodCount: Long,
    val fieldCount: Long,
    val stringCount: Long,
    val skippedStringCount: Long,
    val indexBytes: Long,
    val startedAtEpochMillis: Long,
    val completedAtEpochMillis: Long,
) {
    val durationMillis: Long
        get() = (completedAtEpochMillis - startedAtEpochMillis).coerceAtLeast(0L)
}

data class DexEvidence(
    val kind: DexEvidenceKind,
    val dexEntry: String,
    val symbol: String,
    val detail: String,
    val matchedTerms: List<String>,
    val score: Int,
)

data class LocalAgentResult(
    val question: String,
    val expandedTerms: List<String>,
    val evidence: List<DexEvidence>,
    val localSummary: String,
    val resultFilePath: String,
    val startedAtEpochMillis: Long,
    val completedAtEpochMillis: Long,
) {
    val durationMillis: Long
        get() = (completedAtEpochMillis - startedAtEpochMillis).coerceAtLeast(0L)
}

class DexIndexException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
