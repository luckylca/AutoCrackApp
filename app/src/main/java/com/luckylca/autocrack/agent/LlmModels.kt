package com.luckylca.autocrack.agent

import com.luckylca.autocrack.analysis.StaticAnalysisReport
import com.luckylca.autocrack.dex.DexIndexSummary
import com.luckylca.autocrack.dex.LocalAgentResult
import java.net.URI

enum class LlmApiProtocol {
    OPENAI_CHAT,
    ANTHROPIC_MESSAGES,
}

data class LlmProviderConfig(
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    val id: String = DEFAULT_PROVIDER_ID,
    val name: String = "默认供应商",
    val protocol: LlmApiProtocol = LlmEndpointNormalizer.protocol(baseUrl),
) {
    fun validated(): LlmProviderConfig {
        val endpoint = LlmEndpointNormalizer.normalize(baseUrl, protocol)
        require(id.trim().length in 1..128) { "供应商 ID 无效" }
        require(name.trim().length in 1..64) { "供应商名称不能为空或过长" }
        require(model.trim().length in 1..128) { "模型名称不能为空或过长" }
        require(apiKey.trim().length in 4..8_192) { "API Key 不能为空或格式异常" }
        return copy(
            id = id.trim(),
            name = name.trim(),
            baseUrl = endpoint,
            model = model.trim(),
            apiKey = apiKey.trim(),
        )
    }

    companion object {
        const val DEFAULT_PROVIDER_ID = "default"
    }
}

data class LlmProviderCatalog(
    val providers: List<LlmProviderConfig> = emptyList(),
    val activeProviderId: String? = null,
) {
    val activeProvider: LlmProviderConfig?
        get() = providers.firstOrNull { it.id == activeProviderId } ?: providers.firstOrNull()

    fun validated(): LlmProviderCatalog {
        val normalized = providers.map(LlmProviderConfig::validated)
        require(normalized.map(LlmProviderConfig::id).distinct().size == normalized.size) {
            "供应商 ID 不能重复"
        }
        val active = activeProviderId?.takeIf { id -> normalized.any { it.id == id } }
            ?: normalized.firstOrNull()?.id
        return copy(providers = normalized, activeProviderId = active)
    }
}

data class LlmAgentAnswer(
    val model: String,
    val endpointHost: String,
    val content: String,
    val requestEvidenceCount: Int,
    val startedAtEpochMillis: Long,
    val completedAtEpochMillis: Long,
) {
    val durationMillis: Long
        get() = (completedAtEpochMillis - startedAtEpochMillis).coerceAtLeast(0L)
}

object LlmEndpointNormalizer {
    private val CLEARTEXT_PROVIDER_HOSTS = setOf("128.241.229.70")

    fun normalize(input: String): String = normalize(input, protocol(input))

    fun normalize(input: String, protocol: LlmApiProtocol): String {
        val trimmed = input.trim().trimEnd('/')
        require(trimmed.isNotBlank()) { "API 地址不能为空" }
        val uri = URI(trimmed)
        val scheme = uri.scheme?.lowercase()
        require(scheme == "https" || scheme == "http") { "外部模型地址只支持 HTTP 或 HTTPS" }
        require(!uri.host.isNullOrBlank()) { "API 地址缺少有效主机名" }
        if (scheme == "http") {
            require(uri.host in CLEARTEXT_PROVIDER_HOSTS) { "该 HTTP 模型地址未被允许；请使用 HTTPS" }
        }
        require(uri.userInfo == null) { "API 地址不能包含用户名或密码" }
        require(uri.fragment == null) { "API 地址不能包含 URL Fragment" }
        val base = when {
            trimmed.endsWith("/chat/completions") -> trimmed.removeSuffix("/chat/completions")
            trimmed.endsWith("/messages") -> trimmed.removeSuffix("/messages")
            else -> trimmed
        }
        return when (protocol) {
            LlmApiProtocol.OPENAI_CHAT -> if (base.endsWith("/v1")) {
                "$base/chat/completions"
            } else {
                "$base/v1/chat/completions"
            }
            LlmApiProtocol.ANTHROPIC_MESSAGES -> if (base.endsWith("/v1")) {
                "$base/messages"
            } else {
                "$base/v1/messages"
            }
        }
    }

    fun modelsEndpoint(config: LlmProviderConfig): String {
        val endpoint = normalize(config.baseUrl, config.protocol)
        val base = when (config.protocol) {
            LlmApiProtocol.OPENAI_CHAT -> endpoint.removeSuffix("/chat/completions")
            LlmApiProtocol.ANTHROPIC_MESSAGES -> endpoint.removeSuffix("/messages")
        }
        return "$base/models"
    }

    fun protocol(endpoint: String): LlmApiProtocol =
        if (URI(endpoint).path?.let { path ->
                path.endsWith("/v1/messages") || path.contains("/anthropic")
            } == true) {
            LlmApiProtocol.ANTHROPIC_MESSAGES
        } else {
            LlmApiProtocol.OPENAI_CHAT
        }
}

object LlmPromptBuilder {
    fun build(
        packageName: String,
        staticReport: StaticAnalysisReport,
        dexIndex: DexIndexSummary,
        localResult: LocalAgentResult,
    ): String {
        val manifest = staticReport.manifest
        val evidenceText = buildString {
            localResult.evidence.take(MAX_EVIDENCE_ITEMS).forEachIndexed { index, item ->
                append(index + 1).append(". [").append(item.kind.name).append("] ")
                append(item.symbol).append(" @ ").append(item.dexEntry)
                append("\n   ").append(item.detail.take(MAX_DETAIL_CHARS))
                append("\n   matched=").append(item.matchedTerms.joinToString())
                append('\n')
            }
        }
        return buildString {
            appendLine("用户问题：${localResult.question}")
            appendLine("目标包名：$packageName")
            appendLine("版本：${manifest.versionName ?: "未知"}")
            appendLine("targetSdk：${manifest.targetSdk ?: "未知"}")
            appendLine("签名证书数量：${staticReport.signing.currentSignerSha256.size}")
            appendLine("DEX 条目：${dexIndex.dexEntryCount}")
            appendLine("定义类：${dexIndex.classCount}")
            appendLine("定义方法：${dexIndex.methodCount}")
            appendLine("定义字段：${dexIndex.fieldCount}")
            appendLine("索引字符串：${dexIndex.stringCount}")
            appendLine("Native 库：${staticReport.nativeLibraryCount}")
            appendLine("结构警告：${staticReport.warnings.size}")
            appendLine()
            appendLine("本地检索摘要：")
            appendLine(localResult.localSummary)
            appendLine()
            appendLine("证据：")
            append(evidenceText.take(MAX_CONTEXT_CHARS))
            appendLine()
            appendLine("要求：")
            appendLine("1. 只基于上面的证据回答，不要假装看过未提供的反编译代码。")
            appendLine("2. 明确区分确认事实、合理推断和无法确认的部分。")
            appendLine("3. 引用证据时写出 [类型] 符号 @ DEX 条目。")
            appendLine("4. 给出下一步应搜索的类、方法、字符串或 native 文件。")
            appendLine("5. 不提供绕过授权、窃取凭据或攻击第三方系统的操作步骤。")
        }.take(MAX_PROMPT_CHARS)
    }

    const val SYSTEM_PROMPT: String =
        "你是 Android APK 静态分析助手。你的结论必须可追溯到用户提供的本地证据；缺少证据时明确说无法确认。"

    private const val MAX_EVIDENCE_ITEMS = 60
    private const val MAX_DETAIL_CHARS = 1_000
    private const val MAX_CONTEXT_CHARS = 45_000
    private const val MAX_PROMPT_CHARS = 55_000
}
