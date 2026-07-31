package com.luckylca.autocrack.apk

enum class InstalledAppKind {
    USER,
    SYSTEM,
}

data class InstalledApp(
    val packageName: String,
    val primaryApkPath: String?,
    val uid: Int?,
    val kind: InstalledAppKind,
)

enum class ApkArtifactKind {
    BASE,
    SPLIT,
}

data class ApkSource(
    val sourcePath: String,
    val fileName: String,
    val kind: ApkArtifactKind,
)

data class ExtractedApk(
    val sourcePath: String,
    val localPath: String,
    val fileName: String,
    val kind: ApkArtifactKind,
    val sizeBytes: Long,
    val sha256: String,
)

data class ExtractionReport(
    val packageName: String,
    val workspacePath: String,
    val artifacts: List<ExtractedApk>,
    val startedAtEpochMillis: Long,
    val completedAtEpochMillis: Long,
) {
    val totalBytes: Long
        get() = artifacts.sumOf(ExtractedApk::sizeBytes)

    val splitCount: Int
        get() = artifacts.count { it.kind == ApkArtifactKind.SPLIT }
}

class PackageOperationException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
