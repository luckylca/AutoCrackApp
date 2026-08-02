package com.luckylca.autocrack.tools

import android.content.Context
import com.luckylca.autocrack.analysis.ApkArchiveSummary
import com.luckylca.autocrack.analysis.ComponentKindSummary
import com.luckylca.autocrack.analysis.ComponentSummary
import com.luckylca.autocrack.analysis.DexFileSummary
import com.luckylca.autocrack.analysis.ManifestSummary
import com.luckylca.autocrack.analysis.NativeLibrarySummary
import com.luckylca.autocrack.analysis.PermissionSummary
import com.luckylca.autocrack.analysis.SigningSummary
import com.luckylca.autocrack.analysis.StaticAnalysisReport
import com.luckylca.autocrack.apk.ApkArtifactKind
import com.luckylca.autocrack.apk.ExtractedApk
import com.luckylca.autocrack.apk.ExtractionReport
import com.luckylca.autocrack.dex.DexIndexSummary
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class WorkspaceSnapshotLoader(context: Context) {
    private val workspacesRoot = File(context.applicationContext.filesDir, WORKSPACES_DIRECTORY)

    suspend fun loadLatest(): LoadedToolWorkspace = withContext(Dispatchers.IO) {
        val workspace = workspacesRoot
            .listFiles()
            .orEmpty()
            .filter(File::isDirectory)
            .flatMap { packageDirectory -> packageDirectory.listFiles().orEmpty().filter(File::isDirectory) }
            .filter { directory ->
                File(directory, STATIC_REPORT_FILE).isFile && File(directory, DEX_SUMMARY_FILE).isFile
            }
            .maxByOrNull(File::lastModified)
            ?: throw AnalysisToolException("没有找到已完成的工作区，请先在“应用”中建立工作区")

        val staticJson = JSONObject(File(workspace, STATIC_REPORT_FILE).readText(Charsets.UTF_8))
        val dexJson = JSONObject(File(workspace, DEX_SUMMARY_FILE).readText(Charsets.UTF_8))
        val staticReport = parseStaticReport(staticJson, workspace)
        val extraction = parseExtraction(staticReport, workspace)
        val dexIndex = parseDexIndex(dexJson, workspace)
        LoadedToolWorkspace(extraction, staticReport, dexIndex)
    }

    private fun parseExtraction(
        report: StaticAnalysisReport,
        workspace: File,
    ): ExtractionReport {
        val archiveByName = report.archives.associateBy(ApkArchiveSummary::artifactFileName)
        val artifacts = archiveByName.values.map { archive ->
            val apkFile = File(workspace, archive.artifactFileName).canonicalFile
            ensureInside(workspace, apkFile)
            if (!apkFile.isFile || apkFile.length() <= 0L) {
                throw AnalysisToolException("工作区 APK 不存在：${apkFile.path}")
            }
            ExtractedApk(
                sourcePath = "",
                localPath = apkFile.path,
                fileName = archive.artifactFileName,
                kind = if (archive.artifactFileName == report.baseApkFileName) {
                    ApkArtifactKind.BASE
                } else {
                    ApkArtifactKind.SPLIT
                },
                sizeBytes = apkFile.length(),
                sha256 = archive.artifactSha256,
            )
        }.sortedBy { artifact -> artifact.kind != ApkArtifactKind.BASE }
        return ExtractionReport(
            packageName = report.packageName,
            workspacePath = workspace.canonicalPath,
            artifacts = artifacts,
            startedAtEpochMillis = report.startedAtEpochMillis,
            completedAtEpochMillis = report.completedAtEpochMillis,
        )
    }

    private fun parseDexIndex(json: JSONObject, workspace: File): DexIndexSummary {
        val database = File(workspace, DEX_DATABASE_FILE).canonicalFile
        val summary = File(workspace, DEX_SUMMARY_FILE).canonicalFile
        ensureInside(workspace, database)
        ensureInside(workspace, summary)
        if (!database.isFile || database.length() <= 0L) {
            throw AnalysisToolException("DEX 索引数据库不存在：${database.path}")
        }
        return DexIndexSummary(
            databasePath = database.path,
            summaryPath = summary.path,
            dexEntryCount = json.getInt("dexEntryCount"),
            classCount = json.getLong("classCount"),
            methodCount = json.getLong("methodCount"),
            fieldCount = json.getLong("fieldCount"),
            stringCount = json.getLong("stringCount"),
            skippedStringCount = json.getLong("skippedStringCount"),
            indexBytes = json.optLong("indexBytes", database.length()),
            startedAtEpochMillis = json.getLong("startedAtEpochMillis"),
            completedAtEpochMillis = json.getLong("completedAtEpochMillis"),
        )
    }

    private fun parseStaticReport(json: JSONObject, workspace: File): StaticAnalysisReport {
        val manifestJson = json.getJSONObject("manifest")
        val signingJson = json.getJSONObject("signing")
        val permissionsJson = json.getJSONObject("permissions")
        val componentsJson = json.getJSONObject("components")
        val archives = json.getJSONArray("archives").mapObjects(::parseArchive)
        return StaticAnalysisReport(
            packageName = json.getString("packageName"),
            baseApkFileName = json.getString("baseApkFileName"),
            manifest = ManifestSummary(
                parsed = manifestJson.getBoolean("parsed"),
                diagnostic = manifestJson.nullableString("diagnostic"),
                manifestPackageName = manifestJson.nullableString("manifestPackageName"),
                versionName = manifestJson.nullableString("versionName"),
                versionCode = manifestJson.nullableLong("versionCode"),
                minSdk = manifestJson.nullableInt("minSdk"),
                targetSdk = manifestJson.nullableInt("targetSdk"),
                compileSdk = manifestJson.nullableInt("compileSdk"),
                debuggable = manifestJson.nullableBoolean("debuggable"),
                allowBackup = manifestJson.nullableBoolean("allowBackup"),
                usesCleartextTraffic = manifestJson.nullableBoolean("usesCleartextTraffic"),
                extractNativeLibs = manifestJson.nullableBoolean("extractNativeLibs"),
            ),
            signing = SigningSummary(
                currentSignerSha256 = signingJson.getJSONArray("currentSignerSha256").stringList(),
                signingHistorySha256 = signingJson.getJSONArray("signingHistorySha256").stringList(),
                diagnostic = signingJson.nullableString("diagnostic"),
            ),
            permissions = PermissionSummary(
                requested = permissionsJson.getJSONArray("requested").stringList(),
                declared = permissionsJson.getJSONArray("declared").stringList(),
            ),
            components = ComponentSummary(
                activities = parseComponent(componentsJson.getJSONObject("activities")),
                services = parseComponent(componentsJson.getJSONObject("services")),
                receivers = parseComponent(componentsJson.getJSONObject("receivers")),
                providers = parseComponent(componentsJson.getJSONObject("providers")),
            ),
            archives = archives,
            reportFilePath = File(workspace, STATIC_REPORT_FILE).canonicalPath,
            startedAtEpochMillis = json.getLong("startedAtEpochMillis"),
            completedAtEpochMillis = json.getLong("completedAtEpochMillis"),
        )
    }

    private fun parseComponent(json: JSONObject): ComponentKindSummary = ComponentKindSummary(
        total = json.getInt("total"),
        exported = json.getInt("exported"),
        names = json.getJSONArray("names").stringList(),
        exportedNames = json.getJSONArray("exportedNames").stringList(),
    )

    private fun parseArchive(json: JSONObject): ApkArchiveSummary = ApkArchiveSummary(
        artifactFileName = json.getString("artifactFileName"),
        artifactSha256 = json.getString("artifactSha256"),
        entryCount = json.getInt("entryCount"),
        uncompressedBytes = json.getLong("uncompressedBytes"),
        compressedBytes = json.getLong("compressedBytes"),
        manifestEntryPresent = json.getBoolean("manifestEntryPresent"),
        resourcesArscPresent = json.getBoolean("resourcesArscPresent"),
        resourceEntryCount = json.getInt("resourceEntryCount"),
        assetEntryCount = json.getInt("assetEntryCount"),
        metaInfEntryCount = json.getInt("metaInfEntryCount"),
        signingEntryCount = json.getInt("signingEntryCount"),
        nestedApkEntryCount = json.getInt("nestedApkEntryCount"),
        dexFiles = json.getJSONArray("dexFiles").mapObjects { dex ->
            DexFileSummary(
                entryName = dex.getString("entryName"),
                sizeBytes = dex.getLong("sizeBytes"),
                compressedSizeBytes = dex.getLong("compressedSizeBytes"),
                dexVersion = dex.nullableString("dexVersion"),
                validMagic = dex.getBoolean("validMagic"),
            )
        },
        nativeLibraries = json.getJSONArray("nativeLibraries").mapObjects { library ->
            NativeLibrarySummary(
                entryName = library.getString("entryName"),
                abi = library.getString("abi"),
                fileName = library.getString("fileName"),
                sizeBytes = library.getLong("sizeBytes"),
                compressedSizeBytes = library.getLong("compressedSizeBytes"),
                elfClass = library.nullableString("elfClass"),
                machine = library.nullableString("machine"),
                validElfMagic = library.getBoolean("validElfMagic"),
                headerHex = library.optString("headerHex"),
                diagnostic = library.nullableString("diagnostic"),
            )
        },
        warnings = json.getJSONArray("warnings").stringList(),
    )

    private fun ensureInside(workspace: File, candidate: File) {
        val root = workspace.canonicalPath
        val path = candidate.canonicalPath
        if (path != root && !path.startsWith(root + File.separator)) {
            throw AnalysisToolException("工作区路径越界：$path")
        }
    }

    private fun JSONObject.nullableString(key: String): String? =
        if (isNull(key)) null else getString(key)

    private fun JSONObject.nullableLong(key: String): Long? =
        if (isNull(key)) null else getLong(key)

    private fun JSONObject.nullableInt(key: String): Int? =
        if (isNull(key)) null else getInt(key)

    private fun JSONObject.nullableBoolean(key: String): Boolean? =
        if (isNull(key)) null else getBoolean(key)

    private fun JSONArray.stringList(): List<String> = buildList {
        repeat(length()) { index -> add(getString(index)) }
    }

    private fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> = buildList {
        repeat(length()) { index -> add(transform(getJSONObject(index))) }
    }

    private companion object {
        const val WORKSPACES_DIRECTORY = "workspaces"
        const val STATIC_REPORT_FILE = "analysis-report.json"
        const val DEX_SUMMARY_FILE = "dex-index-summary.json"
        const val DEX_DATABASE_FILE = "dex-index.db"
    }
}

data class LoadedToolWorkspace(
    val extraction: ExtractionReport,
    val staticReport: StaticAnalysisReport,
    val dexIndex: DexIndexSummary,
)
