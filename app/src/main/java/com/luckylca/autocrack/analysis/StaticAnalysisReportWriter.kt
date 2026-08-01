package com.luckylca.autocrack.analysis

import java.io.File
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

object StaticAnalysisReportWriter {
    fun write(report: StaticAnalysisReport, destination: File) {
        try {
            val root = JSONObject()
                .put("schemaVersion", 2)
                .put("packageName", report.packageName)
                .put("baseApkFileName", report.baseApkFileName)
                .put("startedAtEpochMillis", report.startedAtEpochMillis)
                .put("completedAtEpochMillis", report.completedAtEpochMillis)
                .put("manifest", manifestJson(report.manifest))
                .put("signing", signingJson(report.signing))
                .put("permissions", permissionJson(report.permissions))
                .put("components", componentJson(report.components))
                .put("archives", JSONArray().apply {
                    report.archives.forEach { archive -> put(archiveJson(archive)) }
                })

            destination.writeText(root.toString(2), Charsets.UTF_8)
        } catch (exception: IOException) {
            throw StaticAnalysisException("无法写入静态分析报告：${exception.message}", exception)
        }
    }

    private fun manifestJson(summary: ManifestSummary): JSONObject = JSONObject()
        .put("parsed", summary.parsed)
        .putNullable("diagnostic", summary.diagnostic)
        .putNullable("manifestPackageName", summary.manifestPackageName)
        .putNullable("versionName", summary.versionName)
        .putNullable("versionCode", summary.versionCode)
        .putNullable("minSdk", summary.minSdk)
        .putNullable("targetSdk", summary.targetSdk)
        .putNullable("compileSdk", summary.compileSdk)
        .putNullable("debuggable", summary.debuggable)
        .putNullable("allowBackup", summary.allowBackup)
        .putNullable("usesCleartextTraffic", summary.usesCleartextTraffic)
        .putNullable("extractNativeLibs", summary.extractNativeLibs)

    private fun signingJson(summary: SigningSummary): JSONObject = JSONObject()
        .put("currentSignerSha256", stringArray(summary.currentSignerSha256))
        .put("signingHistorySha256", stringArray(summary.signingHistorySha256))
        .putNullable("diagnostic", summary.diagnostic)

    private fun permissionJson(summary: PermissionSummary): JSONObject = JSONObject()
        .put("requested", stringArray(summary.requested))
        .put("declared", stringArray(summary.declared))

    private fun componentJson(summary: ComponentSummary): JSONObject = JSONObject()
        .put("activities", componentKindJson(summary.activities))
        .put("services", componentKindJson(summary.services))
        .put("receivers", componentKindJson(summary.receivers))
        .put("providers", componentKindJson(summary.providers))

    private fun componentKindJson(summary: ComponentKindSummary): JSONObject = JSONObject()
        .put("total", summary.total)
        .put("exported", summary.exported)
        .put("names", stringArray(summary.names))
        .put("exportedNames", stringArray(summary.exportedNames))

    private fun archiveJson(summary: ApkArchiveSummary): JSONObject = JSONObject()
        .put("artifactFileName", summary.artifactFileName)
        .put("artifactSha256", summary.artifactSha256)
        .put("entryCount", summary.entryCount)
        .put("uncompressedBytes", summary.uncompressedBytes)
        .put("compressedBytes", summary.compressedBytes)
        .put("manifestEntryPresent", summary.manifestEntryPresent)
        .put("resourcesArscPresent", summary.resourcesArscPresent)
        .put("resourceEntryCount", summary.resourceEntryCount)
        .put("assetEntryCount", summary.assetEntryCount)
        .put("metaInfEntryCount", summary.metaInfEntryCount)
        .put("signingEntryCount", summary.signingEntryCount)
        .put("nestedApkEntryCount", summary.nestedApkEntryCount)
        .put("abis", stringArray(summary.abis))
        .put("dexFiles", JSONArray().apply {
            summary.dexFiles.forEach { dex ->
                put(
                    JSONObject()
                        .put("entryName", dex.entryName)
                        .put("sizeBytes", dex.sizeBytes)
                        .put("compressedSizeBytes", dex.compressedSizeBytes)
                        .putNullable("dexVersion", dex.dexVersion)
                        .put("validMagic", dex.validMagic),
                )
            }
        })
        .put("nativeLibraries", JSONArray().apply {
            summary.nativeLibraries.forEach { library ->
                put(
                    JSONObject()
                        .put("entryName", library.entryName)
                        .put("abi", library.abi)
                        .put("fileName", library.fileName)
                        .put("sizeBytes", library.sizeBytes)
                        .put("compressedSizeBytes", library.compressedSizeBytes)
                        .putNullable("elfClass", library.elfClass)
                        .putNullable("machine", library.machine)
                        .put("validElfMagic", library.validElfMagic)
                        .put("headerHex", library.headerHex)
                        .putNullable("diagnostic", library.diagnostic),
                )
            }
        })
        .put("warnings", stringArray(summary.warnings))

    private fun stringArray(values: Iterable<String>): JSONArray = JSONArray().apply {
        values.forEach { value -> put(value) }
    }

    private fun JSONObject.putNullable(key: String, value: Any?): JSONObject =
        put(key, value ?: JSONObject.NULL)
}
