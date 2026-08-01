package com.luckylca.autocrack.tools

import android.database.sqlite.SQLiteDatabase
import com.luckylca.autocrack.analysis.NativeLibrarySummary
import com.luckylca.autocrack.analysis.StaticAnalysisReport
import com.luckylca.autocrack.apk.ExtractionReport
import com.luckylca.autocrack.dex.DexIndexSummary
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class AnalysisToolExecutor(
    private val elfInspector: ElfInspector = ElfInspector(),
) {
    suspend fun execute(
        toolId: AnalysisToolId,
        input: String,
        extraction: ExtractionReport,
        staticReport: StaticAnalysisReport,
        dexIndex: DexIndexSummary,
    ): AnalysisToolResult = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val workspace = validateWorkspace(extraction)
        val execution = when (toolId) {
            AnalysisToolId.APK_OVERVIEW -> apkOverview(extraction, staticReport)
            AnalysisToolId.DEX_OVERVIEW -> dexOverview(dexIndex)
            AnalysisToolId.DEX_SEARCH -> dexSearch(extraction.packageName, input, dexIndex)
            AnalysisToolId.DEX_NATIVE_METHODS -> dexNativeMethods(extraction.packageName, dexIndex)
            AnalysisToolId.SO_OVERVIEW -> soOverview(staticReport)
            AnalysisToolId.ELF_INSPECT -> elfInspect(input, extraction, staticReport)
        }
        val completedAt = System.currentTimeMillis()
        val outputDirectory = File(workspace, TOOL_OUTPUT_DIRECTORY).canonicalFile
        ensureInsideWorkspace(workspace, outputDirectory)
        if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
            throw AnalysisToolException("无法创建工具输出目录：${outputDirectory.path}")
        }
        val outputFile = File(
            outputDirectory,
            "$completedAt-${toolId.name.lowercase(Locale.ROOT)}.json",
        ).canonicalFile
        ensureInsideWorkspace(workspace, outputFile)
        val result = AnalysisToolResult(
            toolId = toolId,
            risk = AnalysisToolRisk.READ_ONLY,
            title = execution.title,
            summary = execution.summary,
            details = execution.details,
            outputFilePath = outputFile.path,
            startedAtEpochMillis = startedAt,
            completedAtEpochMillis = completedAt,
        )
        writeResult(result, execution.extraJson, outputFile)
        result
    }

    private fun apkOverview(
        extraction: ExtractionReport,
        report: StaticAnalysisReport,
    ): ToolExecution = ToolExecution(
        title = "${extraction.packageName} APK 总览",
        summary = "已汇总 ${extraction.artifacts.size} 个 APK、${report.dexFileCount} 个 DEX 和 " +
            "${report.nativeLibraryCount} 个 SO。",
        details = buildList {
            add("包名：${extraction.packageName}")
            add("Base APK：${report.baseApkFileName}")
            add("版本：${report.manifest.versionName ?: "未知"} (${report.manifest.versionCode ?: "未知"})")
            add("SDK：min=${report.manifest.minSdk ?: "未知"}, target=${report.manifest.targetSdk ?: "未知"}")
            add("debuggable=${report.manifest.debuggable}, allowBackup=${report.manifest.allowBackup}")
            add("usesCleartextTraffic=${report.manifest.usesCleartextTraffic}")
            add("请求权限：${report.permissions.requested.size}")
            add("导出 Activity：${report.components.activities.exported}/${report.components.activities.total}")
            add("导出 Service：${report.components.services.exported}/${report.components.services.total}")
            add("导出 Receiver：${report.components.receivers.exported}/${report.components.receivers.total}")
            add("导出 Provider：${report.components.providers.exported}/${report.components.providers.total}")
            extraction.artifacts.forEach { artifact ->
                add("APK ${artifact.fileName}：${artifact.sizeBytes} B，sha256=${artifact.sha256}")
            }
            report.warnings.take(MAX_TOOL_LINES).forEach { warning -> add("警告：$warning") }
        }.take(MAX_TOOL_LINES),
    )

    private fun dexOverview(index: DexIndexSummary): ToolExecution = ToolExecution(
        title = "DEX 索引总览",
        summary = "${index.dexEntryCount} 个 DEX 条目，${index.classCount} 个类，" +
            "${index.methodCount} 个方法。",
        details = listOf(
            "类：${index.classCount}",
            "方法：${index.methodCount}",
            "字段：${index.fieldCount}",
            "字符串：${index.stringCount}",
            "跳过字符串：${index.skippedStringCount}",
            "索引大小：${index.indexBytes} B",
            "索引耗时：${index.durationMillis} ms",
            "数据库：${index.databasePath}",
            "摘要：${index.summaryPath}",
        ),
    )

    private fun dexSearch(
        packageName: String,
        rawInput: String,
        index: DexIndexSummary,
    ): ToolExecution {
        val query = rawInput.trim().lowercase(Locale.ROOT)
        if (query.length < MIN_DEX_QUERY_CHARS) {
            throw AnalysisToolException("DEX 搜索至少输入 $MIN_DEX_QUERY_CHARS 个字符")
        }
        val databaseFile = validateDexDatabase(index)
        val escaped = escapeLike(query)
        val rows = mutableListOf<DexSearchRow>()
        val database = SQLiteDatabase.openDatabase(
            databaseFile.path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
        try {
            database.rawQuery(
                """
                SELECT kind, dex_entry, symbol, detail
                FROM evidence
                WHERE search_text LIKE ? ESCAPE '!'
                LIMIT $MAX_DEX_SEARCH_ROWS
                """.trimIndent(),
                arrayOf("%$escaped%"),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    rows += DexSearchRow(
                        kind = cursor.getString(0),
                        dexEntry = cursor.getString(1),
                        symbol = cursor.getString(2),
                        detail = cursor.getString(3),
                    )
                }
            }
        } finally {
            database.close()
        }
        val sorted = rows.sortedWith(
            compareByDescending<DexSearchRow> { row -> isFirstParty(row.symbol, packageName) }
                .thenBy { row -> dexKindOrder(row.kind) }
                .thenBy(DexSearchRow::symbol),
        )
        val firstPartyCount = sorted.count { row -> isFirstParty(row.symbol, packageName) }
        return ToolExecution(
            title = "DEX 搜索：$rawInput",
            summary = "命中 ${sorted.size} 条，其中目标包命名空间 $firstPartyCount 条。",
            details = sorted.take(MAX_TOOL_LINES).map { row ->
                val scope = if (isFirstParty(row.symbol, packageName)) "FIRST_PARTY" else "DEPENDENCY"
                "[$scope][${row.kind}] ${row.symbol} @ ${row.dexEntry} | ${row.detail}"
            },
        )
    }

    private fun dexNativeMethods(
        packageName: String,
        index: DexIndexSummary,
    ): ToolExecution {
        val databaseFile = validateDexDatabase(index)
        val rows = mutableListOf<DexSearchRow>()
        val database = SQLiteDatabase.openDatabase(
            databaseFile.path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
        try {
            database.rawQuery(
                """
                SELECT kind, dex_entry, symbol, detail
                FROM evidence
                WHERE kind = 'METHOD' AND detail LIKE '%; native'
                LIMIT $MAX_NATIVE_METHOD_ROWS
                """.trimIndent(),
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    rows += DexSearchRow(
                        kind = cursor.getString(0),
                        dexEntry = cursor.getString(1),
                        symbol = cursor.getString(2),
                        detail = cursor.getString(3),
                    )
                }
            }
        } finally {
            database.close()
        }
        val sorted = rows.sortedWith(
            compareByDescending<DexSearchRow> { row -> isFirstParty(row.symbol, packageName) }
                .thenBy(DexSearchRow::symbol),
        )
        val firstPartyCount = sorted.count { row -> isFirstParty(row.symbol, packageName) }
        return ToolExecution(
            title = "DEX Native 方法清单",
            summary = "发现 ${sorted.size} 个 native 方法，目标包命名空间 $firstPartyCount 个。",
            details = sorted.take(MAX_TOOL_LINES).map { row ->
                val scope = if (isFirstParty(row.symbol, packageName)) "FIRST_PARTY" else "DEPENDENCY"
                "[$scope] ${row.symbol} @ ${row.dexEntry}"
            },
        )
    }

    private fun soOverview(report: StaticAnalysisReport): ToolExecution {
        val libraries = report.archives.flatMap { archive ->
            archive.nativeLibraries.map { library -> archive.artifactFileName to library }
        }
        return ToolExecution(
            title = "SO / ELF 清单",
            summary = "发现 ${libraries.size} 个 SO，覆盖 " +
                "${libraries.map { it.second.abi }.distinct().size} 个 ABI。",
            details = libraries.take(MAX_TOOL_LINES).map { (apkName, library) ->
                buildString {
                    append(apkName).append("!").append(library.entryName)
                    append(" | ").append(library.elfClass ?: "未知")
                    append(" | ").append(library.machine ?: "未知")
                    append(" | ").append(library.sizeBytes).append(" B")
                    library.diagnostic?.let { diagnostic -> append(" | diagnostic=").append(diagnostic) }
                }
            },
        )
    }

    private fun elfInspect(
        rawInput: String,
        extraction: ExtractionReport,
        report: StaticAnalysisReport,
    ): ToolExecution {
        val query = rawInput.trim()
        if (query.length < MIN_ELF_QUERY_CHARS) {
            throw AnalysisToolException("请输入 SO 文件名或 APK 内 entry，例如 libfoo.so")
        }
        val targets = report.archives.flatMap { archive ->
            archive.nativeLibraries.map { library -> NativeTarget(archive.artifactFileName, library) }
        }
        val exactMatches = targets.filter { target ->
            target.library.fileName.equals(query, ignoreCase = true) ||
                target.library.entryName.equals(query, ignoreCase = true)
        }
        val matches = if (exactMatches.isNotEmpty()) {
            exactMatches
        } else {
            targets.filter { target ->
                target.library.fileName.contains(query, ignoreCase = true) ||
                    target.library.entryName.contains(query, ignoreCase = true)
            }
        }
        if (matches.isEmpty()) {
            throw AnalysisToolException("没有找到 SO：$query")
        }
        if (matches.size > 1) {
            val choices = matches.take(8).joinToString { target ->
                "${target.apkFileName}!${target.library.entryName}"
            }
            throw AnalysisToolException("SO 名称不唯一，请输入完整 entry：$choices")
        }

        val target = matches.single()
        val artifact = extraction.artifacts.firstOrNull { artifact ->
            artifact.fileName == target.apkFileName
        } ?: throw AnalysisToolException("无法定位 SO 所属 APK：${target.apkFileName}")
        val apkFile = File(artifact.localPath).canonicalFile
        val bytes = try {
            ZipFile(apkFile).use { zipFile ->
                val entry = zipFile.getEntry(target.library.entryName)
                    ?: throw AnalysisToolException("APK 中缺少 SO entry：${target.library.entryName}")
                if (entry.size > MAX_ELF_BYTES) {
                    throw AnalysisToolException("SO 超过单次工具限制：${entry.size} B")
                }
                zipFile.getInputStream(entry).use { input -> readBounded(input.readBytes(), MAX_ELF_BYTES) }
            }
        } catch (exception: IOException) {
            throw AnalysisToolException("读取 SO 失败：${exception.message}", exception)
        }
        val sourceLabel = "${target.apkFileName}!${target.library.entryName}"
        val elf = elfInspector.inspect(bytes, sourceLabel)
        val details = buildList {
            add("来源：${elf.sourceLabel}")
            add("文件：${elf.elfClass} / ${elf.byteOrder} / ${elf.objectType} / ${elf.machine}")
            add("入口点：0x${elf.entryPoint.toString(16)}")
            add("Build ID：${elf.buildId ?: "无"}")
            add("SONAME：${elf.soname ?: "无"}")
            add("依赖：${elf.neededLibraries.joinToString().ifBlank { "无" }}")
            add("RPATH：${elf.rpath ?: "无"}")
            add("RUNPATH：${elf.runpath ?: "无"}")
            add("加固：NX=${elf.hardening.nx}, RELRO=${elf.hardening.relro}, " +
                "BIND_NOW=${elf.hardening.bindNow}, Canary=${elf.hardening.stackCanary}, " +
                "FORTIFY=${elf.hardening.fortifiedFunctions}, stripped=${elf.hardening.stripped}")
            add("导入符号：${elf.importedSymbols.size}（报告有界展示）")
            elf.importedSymbols.take(MAX_SYMBOL_PREVIEW).forEach { symbol -> add("IMPORT $symbol") }
            add("导出符号：${elf.exportedSymbols.size}（报告有界展示）")
            elf.exportedSymbols.take(MAX_SYMBOL_PREVIEW).forEach { symbol -> add("EXPORT $symbol") }
            elf.jniSymbols.forEach { symbol -> add("JNI $symbol") }
            elf.interestingStrings.take(MAX_STRING_PREVIEW).forEach { value -> add("STRING $value") }
            elf.diagnostics.forEach { diagnostic -> add("DIAGNOSTIC $diagnostic") }
        }.take(MAX_TOOL_LINES)
        return ToolExecution(
            title = "ELF 深度分析：${target.library.fileName}",
            summary = "解析 ${elf.sections.size} 个节区、${elf.segments.size} 个段、" +
                "${elf.neededLibraries.size} 个依赖和 ${elf.jniSymbols.size} 个 JNI 线索。",
            details = details,
            extraJson = elfJson(elf),
        )
    }

    private fun validateDexDatabase(index: DexIndexSummary): File {
        val file = File(index.databasePath).canonicalFile
        if (!file.isFile || file.length() <= 0L) {
            throw AnalysisToolException("DEX 索引数据库不存在：${file.path}")
        }
        return file
    }

    private fun validateWorkspace(extraction: ExtractionReport): File {
        val workspace = File(extraction.workspacePath).canonicalFile
        if (!workspace.isDirectory) {
            throw AnalysisToolException("工作区不存在：${workspace.path}")
        }
        return workspace
    }

    private fun readBounded(bytes: ByteArray, maxBytes: Long): ByteArray {
        if (bytes.size.toLong() > maxBytes) {
            throw AnalysisToolException("SO 解压后超过单次工具限制：${bytes.size} B")
        }
        return bytes
    }

    private fun writeResult(result: AnalysisToolResult, extra: JSONObject?, destination: File) {
        try {
            val json = JSONObject()
                .put("schemaVersion", 1)
                .put("toolId", result.toolId.name)
                .put("risk", result.risk.name)
                .put("title", result.title)
                .put("summary", result.summary)
                .put("details", JSONArray(result.details))
                .put("startedAtEpochMillis", result.startedAtEpochMillis)
                .put("completedAtEpochMillis", result.completedAtEpochMillis)
                .put("durationMillis", result.durationMillis)
            if (extra != null) json.put("data", extra)
            destination.writeText(json.toString(2), Charsets.UTF_8)
        } catch (exception: IOException) {
            throw AnalysisToolException("无法写入工具结果：${exception.message}", exception)
        }
    }

    private fun elfJson(report: ElfAnalysisReport): JSONObject = JSONObject()
        .put("sourceLabel", report.sourceLabel)
        .put("fileSizeBytes", report.fileSizeBytes)
        .put("elfClass", report.elfClass)
        .put("byteOrder", report.byteOrder)
        .put("objectType", report.objectType)
        .put("machine", report.machine)
        .put("entryPoint", report.entryPoint)
        .put("buildId", report.buildId ?: JSONObject.NULL)
        .put("soname", report.soname ?: JSONObject.NULL)
        .put("neededLibraries", JSONArray(report.neededLibraries))
        .put("rpath", report.rpath ?: JSONObject.NULL)
        .put("runpath", report.runpath ?: JSONObject.NULL)
        .put("hardening", JSONObject()
            .put("nx", report.hardening.nx)
            .put("relro", report.hardening.relro)
            .put("bindNow", report.hardening.bindNow)
            .put("stackCanary", report.hardening.stackCanary)
            .put("fortifiedFunctions", report.hardening.fortifiedFunctions)
            .put("stripped", report.hardening.stripped)
            .put("positionIndependent", report.hardening.positionIndependent))
        .put("sections", JSONArray().apply {
            report.sections.forEach { section ->
                put(JSONObject()
                    .put("index", section.index)
                    .put("name", section.name)
                    .put("type", section.type)
                    .put("offset", section.offset)
                    .put("size", section.size)
                    .put("flags", section.flags))
            }
        })
        .put("segments", JSONArray().apply {
            report.segments.forEach { segment ->
                put(JSONObject()
                    .put("index", segment.index)
                    .put("type", segment.type)
                    .put("offset", segment.offset)
                    .put("fileSize", segment.fileSize)
                    .put("memorySize", segment.memorySize)
                    .put("flags", segment.flags))
            }
        })
        .put("importedSymbols", JSONArray(report.importedSymbols))
        .put("exportedSymbols", JSONArray(report.exportedSymbols))
        .put("jniSymbols", JSONArray(report.jniSymbols))
        .put("interestingStrings", JSONArray(report.interestingStrings))
        .put("diagnostics", JSONArray(report.diagnostics))

    private fun ensureInsideWorkspace(workspace: File, candidate: File) {
        val workspacePath = workspace.canonicalFile.path
        val candidatePath = candidate.canonicalFile.path
        if (candidatePath != workspacePath && !candidatePath.startsWith(workspacePath + File.separator)) {
            throw AnalysisToolException("检测到工具输出路径越界：$candidatePath")
        }
    }

    private fun escapeLike(value: String): String = value
        .replace("!", "!!")
        .replace("%", "!%")
        .replace("_", "!_")

    private fun isFirstParty(symbol: String, packageName: String): Boolean =
        symbol == packageName || symbol.startsWith("$packageName.")

    private fun dexKindOrder(kind: String): Int = when (kind) {
        "METHOD" -> 0
        "CLASS" -> 1
        "FIELD" -> 2
        "STRING" -> 3
        else -> 4
    }

    private data class ToolExecution(
        val title: String,
        val summary: String,
        val details: List<String>,
        val extraJson: JSONObject? = null,
    )

    private data class DexSearchRow(
        val kind: String,
        val dexEntry: String,
        val symbol: String,
        val detail: String,
    )

    private data class NativeTarget(
        val apkFileName: String,
        val library: NativeLibrarySummary,
    )

    private companion object {
        const val TOOL_OUTPUT_DIRECTORY = "tool-results"
        const val MIN_DEX_QUERY_CHARS = 2
        const val MIN_ELF_QUERY_CHARS = 3
        const val MAX_DEX_SEARCH_ROWS = 500
        const val MAX_NATIVE_METHOD_ROWS = 5_000
        const val MAX_TOOL_LINES = 300
        const val MAX_SYMBOL_PREVIEW = 60
        const val MAX_STRING_PREVIEW = 80
        const val MAX_ELF_BYTES = 128L * 1024L * 1024L
    }
}
