package com.luckylca.autocrack.agent

import android.database.sqlite.SQLiteDatabase
import com.luckylca.autocrack.dex.DexEvidence
import com.luckylca.autocrack.dex.DexEvidenceKind
import com.luckylca.autocrack.dex.DexIndexException
import com.luckylca.autocrack.dex.DexIndexSummary
import com.luckylca.autocrack.dex.LocalAgentResult
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal object SqlLikePatternEscaper {
    const val ESCAPE_CHARACTER: Char = '!'

    fun escape(value: String): String = value
        .replace("!", "!!")
        .replace("%", "!%")
        .replace("_", "!_")
}

class LocalEvidenceSearchEngine {
    suspend fun answer(
        packageName: String,
        question: String,
        indexSummary: DexIndexSummary,
    ): LocalAgentResult = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val normalizedQuestion = question.trim()
        if (normalizedQuestion.length < MIN_QUESTION_CHARS) {
            throw DexIndexException("分析问题太短，请至少输入 $MIN_QUESTION_CHARS 个字符")
        }

        val terms = AgentQuestionPlanner.expandTerms(normalizedQuestion)
        if (terms.isEmpty()) {
            throw DexIndexException("没有从问题中提取到可搜索的关键词")
        }

        val databaseFile = File(indexSummary.databasePath).canonicalFile
        if (!databaseFile.isFile || databaseFile.length() <= 0L) {
            throw DexIndexException("DEX 索引数据库不存在：${databaseFile.path}")
        }
        val workspace = databaseFile.parentFile?.canonicalFile
            ?: throw DexIndexException("无法确定 DEX 索引工作目录")

        val candidates = linkedMapOf<String, Candidate>()
        val database = SQLiteDatabase.openDatabase(
            databaseFile.path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
        try {
            terms.forEach { term ->
                queryTerm(database, term).forEach { row ->
                    val key = "${row.kind}|${row.dexEntry}|${row.symbol}"
                    val candidate = candidates.getOrPut(key) { Candidate(row) }
                    candidate.matchedTerms += term
                }
            }
        } finally {
            database.close()
        }

        val evidence = candidates.values
            .map { candidate -> candidate.toEvidence(terms) }
            .sortedWith(
                compareByDescending<DexEvidence>(DexEvidence::score)
                    .thenBy(DexEvidence::kind)
                    .thenBy(DexEvidence::symbol),
            )
            .take(MAX_FINAL_EVIDENCE)

        val completedAt = System.currentTimeMillis()
        val resultFile = File(workspace, "agent-query-$completedAt.json").canonicalFile
        ensureInsideWorkspace(workspace, resultFile)
        val localSummary = buildLocalSummary(packageName, normalizedQuestion, terms, evidence)
        val result = LocalAgentResult(
            question = normalizedQuestion,
            expandedTerms = terms,
            evidence = evidence,
            localSummary = localSummary,
            resultFilePath = resultFile.path,
            startedAtEpochMillis = startedAt,
            completedAtEpochMillis = completedAt,
        )
        writeResult(packageName, result, resultFile)
        result
    }

    private fun queryTerm(database: SQLiteDatabase, term: String): List<SearchRow> {
        val escaped = SqlLikePatternEscaper.escape(term.lowercase(Locale.ROOT))
        val rows = mutableListOf<SearchRow>()
        database.rawQuery(
            """
            SELECT kind, dex_entry, symbol, detail, search_text
            FROM evidence
            WHERE search_text LIKE ? ESCAPE '!'
            LIMIT $MAX_ROWS_PER_TERM
            """.trimIndent(),
            arrayOf("%$escaped%"),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val kind = runCatching {
                    DexEvidenceKind.valueOf(cursor.getString(0))
                }.getOrNull() ?: continue
                rows += SearchRow(
                    kind = kind,
                    dexEntry = cursor.getString(1),
                    symbol = cursor.getString(2),
                    detail = cursor.getString(3),
                    searchText = cursor.getString(4),
                )
            }
        }
        return rows
    }

    private fun Candidate.toEvidence(allTerms: List<String>): DexEvidence {
        val matched = allTerms.filter { term ->
            row.searchText.contains(term, ignoreCase = true) ||
                row.symbol.contains(term, ignoreCase = true)
        }.distinct()
        val exactBonus = matched.sumOf { term ->
            when {
                row.symbol.equals(term, ignoreCase = true) -> 30
                row.symbol.contains(".$term", ignoreCase = true) -> 18
                row.symbol.contains(term, ignoreCase = true) -> 10
                else -> 4
            }
        }
        val kindScore = when (row.kind) {
            DexEvidenceKind.METHOD -> 50
            DexEvidenceKind.CLASS -> 42
            DexEvidenceKind.FIELD -> 34
            DexEvidenceKind.STRING -> 28
        }
        val diversityBonus = matched.size.coerceAtMost(8) * 8
        return DexEvidence(
            kind = row.kind,
            dexEntry = row.dexEntry,
            symbol = row.symbol,
            detail = row.detail,
            matchedTerms = matched,
            score = kindScore + exactBonus + diversityBonus,
        )
    }

    private fun buildLocalSummary(
        packageName: String,
        question: String,
        terms: List<String>,
        evidence: List<DexEvidence>,
    ): String {
        if (evidence.isEmpty()) {
            return buildString {
                append("没有在 ").append(packageName).append(" 的当前 DEX 索引中找到直接匹配证据。")
                append("这不等于目标行为不存在；代码可能被混淆、动态加载、放在 native 层，或使用了未覆盖的词汇。")
                append("\n搜索词：").append(terms.joinToString())
            }
        }

        val counts = evidence.groupingBy(DexEvidence::kind).eachCount()
        return buildString {
            append("问题：").append(question).append('\n')
            append("在 ").append(packageName).append(" 的 DEX 符号和字符串索引中找到 ")
            append(evidence.size).append(" 条高相关证据：")
            append("类 ").append(counts[DexEvidenceKind.CLASS] ?: 0).append("，")
            append("方法 ").append(counts[DexEvidenceKind.METHOD] ?: 0).append("，")
            append("字段 ").append(counts[DexEvidenceKind.FIELD] ?: 0).append("，")
            append("字符串 ").append(counts[DexEvidenceKind.STRING] ?: 0).append("。")
            append("这些结果只能证明相关符号或常量存在，不能单独证明运行时一定执行了对应路径。")
            append("\n搜索词：").append(terms.joinToString())
            append("\n优先证据：")
            evidence.take(8).forEachIndexed { index, item ->
                append("\n").append(index + 1).append(". [").append(item.kind.name).append("] ")
                append(item.symbol).append(" @ ").append(item.dexEntry)
            }
        }
    }

    private fun writeResult(packageName: String, result: LocalAgentResult, destination: File) {
        try {
            val json = JSONObject()
                .put("schemaVersion", 1)
                .put("packageName", packageName)
                .put("question", result.question)
                .put("expandedTerms", JSONArray(result.expandedTerms))
                .put("localSummary", result.localSummary)
                .put("startedAtEpochMillis", result.startedAtEpochMillis)
                .put("completedAtEpochMillis", result.completedAtEpochMillis)
                .put("evidence", JSONArray().apply {
                    result.evidence.forEach { item ->
                        put(
                            JSONObject()
                                .put("kind", item.kind.name)
                                .put("dexEntry", item.dexEntry)
                                .put("symbol", item.symbol)
                                .put("detail", item.detail)
                                .put("matchedTerms", JSONArray(item.matchedTerms))
                                .put("score", item.score),
                        )
                    }
                })
            destination.writeText(json.toString(2), Charsets.UTF_8)
        } catch (exception: IOException) {
            throw DexIndexException("无法写入 Agent 本地结果：${exception.message}", exception)
        }
    }

    private fun ensureInsideWorkspace(workspace: File, candidate: File) {
        val prefix = workspace.canonicalFile.path + File.separator
        if (!candidate.canonicalFile.path.startsWith(prefix)) {
            throw DexIndexException("检测到 Agent 结果路径越界：${candidate.path}")
        }
    }

    private data class SearchRow(
        val kind: DexEvidenceKind,
        val dexEntry: String,
        val symbol: String,
        val detail: String,
        val searchText: String,
    )

    private data class Candidate(
        val row: SearchRow,
        val matchedTerms: MutableSet<String> = linkedSetOf(),
    )

    private companion object {
        const val MIN_QUESTION_CHARS = 4
        const val MAX_ROWS_PER_TERM = 120
        const val MAX_FINAL_EVIDENCE = 80
    }
}
