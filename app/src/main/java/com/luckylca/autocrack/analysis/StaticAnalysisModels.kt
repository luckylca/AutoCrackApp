package com.luckylca.autocrack.analysis

data class StaticAnalysisReport(
    val packageName: String,
    val baseApkFileName: String,
    val manifest: ManifestSummary,
    val signing: SigningSummary,
    val permissions: PermissionSummary,
    val components: ComponentSummary,
    val archives: List<ApkArchiveSummary>,
    val reportFilePath: String,
    val startedAtEpochMillis: Long,
    val completedAtEpochMillis: Long,
) {
    val dexFileCount: Int
        get() = archives.sumOf { it.dexFiles.size }

    val nativeLibraryCount: Int
        get() = archives.sumOf { it.nativeLibraries.size }

    val totalZipEntries: Int
        get() = archives.sumOf(ApkArchiveSummary::entryCount)

    val warnings: List<String>
        get() = archives.flatMap(ApkArchiveSummary::warnings)

    val nativeLibraryDiagnostics: List<NativeLibrarySummary>
        get() = archives
            .flatMap(ApkArchiveSummary::nativeLibraries)
            .filter { library -> library.diagnostic != null }
}

data class ManifestSummary(
    val parsed: Boolean,
    val diagnostic: String?,
    val manifestPackageName: String?,
    val versionName: String?,
    val versionCode: Long?,
    val minSdk: Int?,
    val targetSdk: Int?,
    val compileSdk: Int?,
    val debuggable: Boolean?,
    val allowBackup: Boolean?,
    val usesCleartextTraffic: Boolean?,
    val extractNativeLibs: Boolean?,
)

data class SigningSummary(
    val currentSignerSha256: List<String>,
    val signingHistorySha256: List<String>,
    val diagnostic: String?,
)

data class PermissionSummary(
    val requested: List<String>,
    val declared: List<String>,
)

data class ComponentSummary(
    val activities: ComponentKindSummary,
    val services: ComponentKindSummary,
    val receivers: ComponentKindSummary,
    val providers: ComponentKindSummary,
)

data class ComponentKindSummary(
    val total: Int,
    val exported: Int,
    val names: List<String>,
    val exportedNames: List<String>,
)

data class ApkArchiveSummary(
    val artifactFileName: String,
    val artifactSha256: String,
    val entryCount: Int,
    val uncompressedBytes: Long,
    val compressedBytes: Long,
    val manifestEntryPresent: Boolean,
    val resourcesArscPresent: Boolean,
    val resourceEntryCount: Int,
    val assetEntryCount: Int,
    val metaInfEntryCount: Int,
    val signingEntryCount: Int,
    val nestedApkEntryCount: Int,
    val dexFiles: List<DexFileSummary>,
    val nativeLibraries: List<NativeLibrarySummary>,
    val warnings: List<String>,
) {
    val abis: List<String>
        get() = nativeLibraries.map(NativeLibrarySummary::abi).distinct().sorted()
}

data class DexFileSummary(
    val entryName: String,
    val sizeBytes: Long,
    val compressedSizeBytes: Long,
    val dexVersion: String?,
    val validMagic: Boolean,
)

data class NativeLibrarySummary(
    val entryName: String,
    val abi: String,
    val fileName: String,
    val sizeBytes: Long,
    val compressedSizeBytes: Long,
    val elfClass: String?,
    val machine: String?,
    val validElfMagic: Boolean,
    val headerHex: String = "",
    val diagnostic: String? = null,
)

class StaticAnalysisException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
