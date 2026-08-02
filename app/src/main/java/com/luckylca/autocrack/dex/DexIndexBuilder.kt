package com.luckylca.autocrack.dex

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import com.luckylca.autocrack.apk.ExtractionReport
import java.io.File
import java.io.IOException
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DexIndexBuilder {
    suspend fun build(extraction: ExtractionReport): DexIndexSummary = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val workspace = validateWorkspace(extraction)
        val databaseFile = File(workspace, DATABASE_FILE_NAME).canonicalFile
        val summaryFile = File(workspace, SUMMARY_FILE_NAME).canonicalFile
        ensureInsideWorkspace(workspace, databaseFile)
        ensureInsideWorkspace(workspace, summaryFile)

        deleteDatabaseFiles(databaseFile)
        if (summaryFile.exists() && !summaryFile.delete()) {
            throw DexIndexException("无法清理旧 DEX 索引摘要：${summaryFile.path}")
        }

        var dexEntryCount = 0
        var classCount = 0L
        var methodCount = 0L
        var fieldCount = 0L
        var stringCount = 0L
        var skippedStringCount = 0L

        val database = SQLiteDatabase.openOrCreateDatabase(databaseFile, null)
        try {
            configureDatabase(database)
            createSchema(database)
            val insertStatement = database.compileStatement(INSERT_EVIDENCE_SQL)

            database.beginTransaction()
            try {
                extraction.artifacts.forEach { artifact ->
                    val apkFile = File(artifact.localPath).canonicalFile
                    ensureInsideWorkspace(workspace, apkFile)
                    val container = DexFileFactory.loadDexContainer(apkFile, Opcodes.getDefault())
                    container.dexEntryNames.sorted().forEach { dexName ->
                        val dexEntry = container.getEntry(dexName)
                            ?: throw DexIndexException("无法读取 DEX 条目：${artifact.fileName}!$dexName")
                        val dexFile = dexEntry.dexFile
                        val qualifiedDexName = "${artifact.fileName}!$dexName"
                        dexEntryCount += 1

                        dexFile.classes.forEach { classDef ->
                            insertClass(insertStatement, qualifiedDexName, classDef)
                            classCount += 1

                            classDef.fields.forEach { field ->
                                insertField(insertStatement, qualifiedDexName, field)
                                fieldCount += 1
                            }
                            classDef.methods.forEach { method ->
                                insertMethod(insertStatement, qualifiedDexName, method)
                                methodCount += 1
                            }
                        }

                        dexFile.stringReferences.forEach { stringReference ->
                            val value = sanitizeString(stringReference.string)
                            if (value == null || stringCount >= MAX_INDEXED_STRINGS) {
                                skippedStringCount += 1
                            } else {
                                insertEvidence(
                                    statement = insertStatement,
                                    kind = DexEvidenceKind.STRING,
                                    dexEntry = qualifiedDexName,
                                    symbol = value.take(MAX_SYMBOL_CHARS),
                                    detail = value,
                                    searchText = value.lowercase(),
                                )
                                stringCount += 1
                            }
                        }
                    }
                }
                putMetadata(database, "dexEntryCount", dexEntryCount.toString())
                putMetadata(database, "classCount", classCount.toString())
                putMetadata(database, "methodCount", methodCount.toString())
                putMetadata(database, "fieldCount", fieldCount.toString())
                putMetadata(database, "stringCount", stringCount.toString())
                putMetadata(database, "skippedStringCount", skippedStringCount.toString())
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        } catch (exception: Exception) {
            deleteDatabaseFiles(databaseFile)
            if (exception is DexIndexException) throw exception
            throw DexIndexException("建立 DEX 索引失败：${exception.message}", exception)
        } finally {
            database.close()
        }

        val completedAt = System.currentTimeMillis()
        val summary = DexIndexSummary(
            databasePath = databaseFile.path,
            summaryPath = summaryFile.path,
            dexEntryCount = dexEntryCount,
            classCount = classCount,
            methodCount = methodCount,
            fieldCount = fieldCount,
            stringCount = stringCount,
            skippedStringCount = skippedStringCount,
            indexBytes = databaseFile.length(),
            startedAtEpochMillis = startedAt,
            completedAtEpochMillis = completedAt,
        )
        writeSummary(summary, summaryFile)
        summary
    }

    private fun configureDatabase(database: SQLiteDatabase) {
        database.execSQL("PRAGMA foreign_keys=OFF")
        database.execSQL("PRAGMA synchronous=NORMAL")
        database.execSQL("PRAGMA temp_store=MEMORY")
    }

    private fun createSchema(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE evidence (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                kind TEXT NOT NULL,
                dex_entry TEXT NOT NULL,
                symbol TEXT NOT NULL,
                detail TEXT NOT NULL,
                search_text TEXT NOT NULL
            )
            """.trimIndent(),
        )
        database.execSQL("CREATE INDEX evidence_kind_index ON evidence(kind)")
        database.execSQL(
            """
            CREATE TABLE metadata (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
            """.trimIndent(),
        )
    }

    private fun insertClass(
        statement: SQLiteStatement,
        dexEntry: String,
        classDef: ClassDef,
    ) {
        val dotType = descriptorToReadable(classDef.type)
        val interfaces = classDef.interfaces.joinToString(",") { descriptorToReadable(it.toString()) }
        val detail = buildString {
            append("descriptor=").append(classDef.type)
            append("; access=0x").append(classDef.accessFlags.toString(16))
            classDef.superclass?.let { append("; super=").append(descriptorToReadable(it)) }
            if (interfaces.isNotBlank()) append("; interfaces=").append(interfaces)
            classDef.sourceFile?.let { append("; source=").append(it) }
        }
        insertEvidence(
            statement = statement,
            kind = DexEvidenceKind.CLASS,
            dexEntry = dexEntry,
            symbol = dotType,
            detail = detail,
            searchText = "$dotType ${classDef.type} $interfaces ${classDef.sourceFile.orEmpty()}".lowercase(),
        )
    }

    private fun insertField(
        statement: SQLiteStatement,
        dexEntry: String,
        field: Field,
    ) {
        val owner = descriptorToReadable(field.definingClass)
        val type = descriptorToReadable(field.type)
        val symbol = "$owner.${field.name}:$type"
        val detail = "descriptor=${field.definingClass}->${field.name}:${field.type}; access=0x${field.accessFlags.toString(16)}"
        insertEvidence(
            statement = statement,
            kind = DexEvidenceKind.FIELD,
            dexEntry = dexEntry,
            symbol = symbol,
            detail = detail,
            searchText = "$symbol ${field.definingClass} ${field.name} ${field.type}".lowercase(),
        )
    }

    private fun insertMethod(
        statement: SQLiteStatement,
        dexEntry: String,
        method: Method,
    ) {
        val owner = descriptorToReadable(method.definingClass)
        val parameters = method.parameterTypes.joinToString(",") { descriptorToReadable(it.toString()) }
        val returnType = descriptorToReadable(method.returnType)
        val symbol = "$owner.${method.name}($parameters):$returnType"
        val rawDescriptor = method.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.toString() } +
            method.returnType
        val implementationState = if (method.implementation == null) "abstract/native" else "code"
        val detail = "descriptor=${method.definingClass}->${method.name}$rawDescriptor; access=0x${method.accessFlags.toString(16)}; $implementationState"
        insertEvidence(
            statement = statement,
            kind = DexEvidenceKind.METHOD,
            dexEntry = dexEntry,
            symbol = symbol,
            detail = detail,
            searchText = "$symbol ${method.definingClass} ${method.name} $rawDescriptor".lowercase(),
        )
    }

    private fun insertEvidence(
        statement: SQLiteStatement,
        kind: DexEvidenceKind,
        dexEntry: String,
        symbol: String,
        detail: String,
        searchText: String,
    ) {
        statement.clearBindings()
        statement.bindString(1, kind.name)
        statement.bindString(2, dexEntry.take(MAX_DEX_ENTRY_CHARS))
        statement.bindString(3, symbol.take(MAX_SYMBOL_CHARS))
        statement.bindString(4, detail.take(MAX_DETAIL_CHARS))
        statement.bindString(5, searchText.take(MAX_SEARCH_TEXT_CHARS))
        statement.executeInsert()
    }

    private fun putMetadata(database: SQLiteDatabase, key: String, value: String) {
        database.execSQL(
            "INSERT OR REPLACE INTO metadata(key, value) VALUES(?, ?)",
            arrayOf(key, value),
        )
    }

    private fun sanitizeString(value: String): String? {
        val normalized = value
            .replace('\u0000', ' ')
            .replace(Regex("[\\p{Cntrl}&&[^\\r\\n\\t]]"), " ")
            .trim()
        if (normalized.length < MIN_INDEXED_STRING_CHARS) return null
        return normalized.take(MAX_INDEXED_STRING_CHARS)
    }

    private fun descriptorToReadable(descriptor: String): String {
        var arrayDepth = 0
        var current = descriptor
        while (current.startsWith("[")) {
            arrayDepth += 1
            current = current.substring(1)
        }
        val base = when (current) {
            "V" -> "void"
            "Z" -> "boolean"
            "B" -> "byte"
            "S" -> "short"
            "C" -> "char"
            "I" -> "int"
            "J" -> "long"
            "F" -> "float"
            "D" -> "double"
            else -> if (current.startsWith("L") && current.endsWith(";")) {
                current.substring(1, current.length - 1).replace('/', '.')
            } else {
                current
            }
        }
        return buildString {
            append(base)
            repeat(arrayDepth) { append("[]") }
        }
    }

    private fun validateWorkspace(extraction: ExtractionReport): File {
        val workspace = File(extraction.workspacePath).canonicalFile
        if (!workspace.isDirectory) {
            throw DexIndexException("提取工作目录不存在：${workspace.path}")
        }
        extraction.artifacts.forEach { artifact ->
            val file = File(artifact.localPath).canonicalFile
            ensureInsideWorkspace(workspace, file)
            if (!file.isFile || file.length() <= 0L) {
                throw DexIndexException("提取 APK 不存在或为空：${artifact.fileName}")
            }
        }
        return workspace
    }

    private fun ensureInsideWorkspace(workspace: File, candidate: File) {
        val prefix = workspace.canonicalFile.path + File.separator
        if (!candidate.canonicalFile.path.startsWith(prefix)) {
            throw DexIndexException("检测到工作目录路径越界：${candidate.path}")
        }
    }

    private fun deleteDatabaseFiles(databaseFile: File) {
        listOf(
            databaseFile,
            File(databaseFile.path + "-journal"),
            File(databaseFile.path + "-wal"),
            File(databaseFile.path + "-shm"),
        ).forEach { file ->
            if (file.exists() && !file.delete()) {
                throw DexIndexException("无法清理旧 DEX 索引文件：${file.path}")
            }
        }
    }

    private fun writeSummary(summary: DexIndexSummary, destination: File) {
        try {
            val json = JSONObject()
                .put("schemaVersion", 1)
                .put("databasePath", summary.databasePath)
                .put("dexEntryCount", summary.dexEntryCount)
                .put("classCount", summary.classCount)
                .put("methodCount", summary.methodCount)
                .put("fieldCount", summary.fieldCount)
                .put("stringCount", summary.stringCount)
                .put("skippedStringCount", summary.skippedStringCount)
                .put("indexBytes", summary.indexBytes)
                .put("startedAtEpochMillis", summary.startedAtEpochMillis)
                .put("completedAtEpochMillis", summary.completedAtEpochMillis)
            destination.writeText(json.toString(2), Charsets.UTF_8)
        } catch (exception: IOException) {
            throw DexIndexException("无法写入 DEX 索引摘要：${exception.message}", exception)
        }
    }

    private companion object {
        const val DATABASE_FILE_NAME = "dex-index.db"
        const val SUMMARY_FILE_NAME = "dex-index-summary.json"
        const val MAX_INDEXED_STRINGS = 750_000L
        const val MIN_INDEXED_STRING_CHARS = 2
        const val MAX_INDEXED_STRING_CHARS = 2_048
        const val MAX_DEX_ENTRY_CHARS = 512
        const val MAX_SYMBOL_CHARS = 2_048
        const val MAX_DETAIL_CHARS = 4_096
        const val MAX_SEARCH_TEXT_CHARS = 6_144
        const val INSERT_EVIDENCE_SQL =
            "INSERT INTO evidence(kind, dex_entry, symbol, detail, search_text) VALUES(?, ?, ?, ?, ?)"
    }
}
