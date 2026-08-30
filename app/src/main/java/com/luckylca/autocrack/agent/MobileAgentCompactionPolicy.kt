package com.luckylca.autocrack.agent

internal object MobileAgentCompactionPolicy {
    private val requiredSections = listOf(
        "## 用户目标与约束",
        "## 已确认事实",
        "## 关键对象与路径",
        "## 已完成操作",
        "## 失败尝试与错误",
        "## 当前状态",
        "## 下一步",
    )
    private val identifierPattern = Regex("(?<![A-Za-z0-9_])[/A-Za-z_$][A-Za-z0-9_.$/:-]{3,}")

    fun outputContract(): String = buildString {
        appendLine("只输出下面格式的检查点，不要添加前言或省略标题：")
        appendLine("# Agent 工作检查点 v1")
        requiredSections.forEach { heading ->
            appendLine(heading)
            appendLine("- 用具体事实填写；确实没有内容时写“无”。")
        }
    }.trim()

    fun generatedSummaryError(summary: String, sourceText: String): String? {
        val normalized = summary.trim()
        if (normalized.length < MIN_GENERATED_SUMMARY_CHARS) {
            return "摘要过短：${normalized.length} < $MIN_GENERATED_SUMMARY_CHARS"
        }
        if (!normalized.startsWith("# Agent 工作检查点 v1")) return "缺少检查点版本标题"
        val missing = requiredSections.filterNot(normalized::contains)
        if (missing.isNotEmpty()) return "缺少必要章节：${missing.joinToString()}"
        if (looksLikePlaceholder(normalized)) return "摘要疑似占位符或截断内容"

        if (normalized.contains("用具体事实填写；确实没有内容时写")) return "摘要复述了输出模板"
        val anchors = distinctiveAnchors(sourceText)
        val retainedAnchorCount = anchors.count(normalized::contains)
        if (retainedAnchorCount < minOf(MIN_RETAINED_ANCHORS, anchors.size)) {
            return "摘要没有保留来源中的关键标识"
        }
        return null
    }

    fun isUsablePersistedSummary(summary: String?): Boolean {
        val normalized = summary?.trim().orEmpty()
        if (normalized.length < MIN_LEGACY_SUMMARY_CHARS || looksLikePlaceholder(normalized)) return false
        if (normalized.startsWith("# Agent 工作检查点")) {
            return requiredSections.all(normalized::contains) && normalized.length >= MIN_GENERATED_SUMMARY_CHARS
        }
        return true
    }

    fun buildSourceContext(messages: List<MobileAgentMessage>, maxChars: Int): String {
        require(maxChars > 0)
        val anchors = recentDistinctiveAnchors(messages)
        val userDirectives = messages.asSequence()
            .filter { it.role == MobileAgentRole.USER }
            .map { it.content.trim() }
            .filter(String::isNotBlank)
            .toList()
            .takeLast(MAX_USER_DIRECTIVES)
        val prefix = buildString {
            appendLine("用户目标和后续纠正（按时间顺序）：")
            userDirectives.forEach { appendLine("- ${abbreviate(it, USER_DIRECTIVE_CHARS)}") }
            if (anchors.isNotEmpty()) {
                appendLine()
                appendLine("全段历史中出现的关键标识（摘要中保留仍有用的项）：")
                appendLine(anchors.joinToString(", "))
            }
            appendLine()
            appendLine("最近的工作记录（越靠后越新）：")
        }
        if (prefix.length >= maxChars) return prefix.take(maxChars)

        val remaining = maxChars - prefix.length
        val selected = mutableListOf<String>()
        var used = 0
        for (message in messages.asReversed()) {
            if (used >= remaining) break
            val record = formatMessage(message)
            val clipped = abbreviate(record, minOf(MAX_HISTORY_RECORD_CHARS, remaining - used))
            if (clipped.isBlank()) continue
            selected += clipped
            used += clipped.length + 2
        }
        return buildString {
            append(prefix)
            selected.asReversed().forEach { append(it).append("\n\n") }
        }.take(maxChars)
    }

    private fun formatMessage(message: MobileAgentMessage): String = buildString {
        val role = when (message.role) {
            MobileAgentRole.USER -> "USER"
            MobileAgentRole.ASSISTANT -> if (message.toolCallsJson != null) "ASSISTANT_TOOL_CALL" else "ASSISTANT"
            MobileAgentRole.TOOL -> "TOOL:${message.toolName.orEmpty()}"
        }
        append(role).append(": ")
        if (message.content.isNotBlank()) append(message.content)
        message.toolCallsJson?.let { calls ->
            if (message.content.isNotBlank()) append('\n')
            append(calls)
        }
        if (message.attachments.isNotEmpty()) {
            append("\nATTACHMENTS: ")
            append(message.attachments.joinToString { "${it.displayName}=${it.agentPath}" })
        }
    }

    private fun distinctiveAnchors(text: String): List<String> = identifierPattern.findAll(text)
        .map { it.value.trimEnd('.', ':', '/') }
        .filter { token ->
            token.length in 4..160 && (
                '/' in token || '.' in token || '_' in token || token.any(Char::isDigit) ||
                    token.drop(1).any(Char::isUpperCase)
                )
        }
        .distinct()
        .toList()

    private fun recentDistinctiveAnchors(messages: List<MobileAgentMessage>): List<String> {
        val anchors = linkedSetOf<String>()
        fun scan(text: String) {
            distinctiveAnchors(text).forEach { anchor ->
                anchors.remove(anchor)
                anchors.add(anchor)
                if (anchors.size > MAX_ANCHORS) anchors.remove(anchors.first())
            }
        }
        messages.forEach { message ->
            scan(message.content)
            message.toolCallsJson?.let(::scan)
            message.attachments.forEach { attachment ->
                scan(attachment.displayName)
                scan(attachment.agentPath)
            }
        }
        return anchors.toList()
    }

    private fun looksLikePlaceholder(text: String): Boolean {
        val compact = text.replace(Regex("[\\s#*`_>-]+"), "")
        return compact.length < MIN_MEANINGFUL_CHARS || compact in setOf("长", "摘要", "总结", "上下文", "无")
    }

    private fun abbreviate(value: String, limit: Int): String {
        if (limit <= 0) return ""
        val normalized = value.trim()
        if (normalized.length <= limit) return normalized
        if (limit < 20) return normalized.take(limit)
        val head = limit * 2 / 3
        val tail = limit - head - 5
        return normalized.take(head) + "\n...\n" + normalized.takeLast(tail)
    }

    private const val MIN_GENERATED_SUMMARY_CHARS = 160
    private const val MIN_LEGACY_SUMMARY_CHARS = 32
    private const val MIN_MEANINGFUL_CHARS = 12
    private const val MAX_ANCHORS = 160
    private const val MIN_RETAINED_ANCHORS = 2
    private const val MAX_USER_DIRECTIVES = 24
    private const val USER_DIRECTIVE_CHARS = 1_000
    private const val MAX_HISTORY_RECORD_CHARS = 4_000
}
