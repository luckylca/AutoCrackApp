package com.luckylca.autocrack.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

@Composable
internal fun MobileAgentMarkdown(text: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val blocks = remember(text) { parseMarkdownBlocks(text) }
    Column(
        modifier = modifier.combinedClickable(
            onClick = {},
            onLongClickLabel = "复制回答",
            onLongClick = {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                    ClipData.newPlainText("Agent answer", text),
                )
                Toast.makeText(context, "回答已复制", Toast.LENGTH_SHORT).show()
            },
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Code -> MarkdownCodeBlock(block.language, block.code)
                is MarkdownBlock.Heading -> Text(
                    text = inlineMarkdown(block.text),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        3 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.bodyLarge
                    },
                    fontWeight = FontWeight.SemiBold,
                )
                MarkdownBlock.HorizontalRule -> HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                is MarkdownBlock.ListItem -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(block.marker, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(inlineMarkdown(block.text), modifier = Modifier.weight(1f))
                }
                is MarkdownBlock.Quote -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("│", color = MaterialTheme.colorScheme.primary)
                    Text(
                        inlineMarkdown(block.text),
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is MarkdownBlock.Paragraph -> Text(inlineMarkdown(block.text))
            }
        }
    }
}

@Composable
private fun MarkdownCodeBlock(language: String?, code: String) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                language?.ifBlank { "code" } ?: "code",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                        ClipData.newPlainText("Agent code", code),
                    )
                    Toast.makeText(context, "代码已复制", Toast.LENGTH_SHORT).show()
                },
            ) { Text("复制") }
        }
        Text(code, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun inlineMarkdown(text: String): AnnotatedString {
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest
    val linkColor = MaterialTheme.colorScheme.primary
    return buildAnnotatedString {
        var cursor = 0
        val token = Regex(
            "(`[^`\\n]+`|\\[[^]\\n]+]\\(https?://[^)\\s]+\\)|\\*\\*[^*\\n]+\\*\\*|__[^_\\n]+__|~~[^~\\n]+~~|(?<!\\*)\\*[^*\\n]+\\*(?!\\*)|(?<!_)_[^_\\n]+_(?!_))",
        )
        token.findAll(text).forEach { match ->
            if (match.range.first > cursor) append(text.substring(cursor, match.range.first))
            val raw = match.value
            when {
                raw.startsWith('`') -> {
                    val start = length
                    append(raw.removePrefix("`").removeSuffix("`"))
                    addStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground), start, length)
                }
                raw.startsWith("**") || raw.startsWith("__") -> {
                    val start = length
                    append(raw.drop(2).dropLast(2))
                    addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), start, length)
                }
                raw.startsWith("~~") -> {
                    val start = length
                    append(raw.drop(2).dropLast(2))
                    addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), start, length)
                }
                raw.startsWith('*') || raw.startsWith('_') -> {
                    val start = length
                    append(raw.drop(1).dropLast(1))
                    addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, length)
                }
                raw.startsWith('[') -> {
                    val close = raw.indexOf("](")
                    val label = raw.substring(1, close)
                    val url = raw.substring(close + 2, raw.length - 1)
                    withLink(LinkAnnotation.Url(url)) {
                        val start = length
                        append(label)
                        addStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline), start, length)
                    }
                }
            }
            cursor = match.range.last + 1
        }
        if (cursor < text.length) append(text.substring(cursor))
    }
}

internal sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class ListItem(val marker: String, val text: String) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class Code(val language: String?, val code: String) : MarkdownBlock
    data object HorizontalRule : MarkdownBlock
}

internal fun parseMarkdownBlocks(text: String): List<MarkdownBlock> {
    val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val result = mutableListOf<MarkdownBlock>()
    val paragraph = mutableListOf<String>()
    var inCode = false
    var codeLanguage: String? = null
    var codeFence = "```"
    val code = StringBuilder()
    val headingPattern = Regex("^(#{1,6})\\s+(.+)$")
    val unorderedListPattern = Regex("^\\s*[-+*]\\s+(.+)$")
    val orderedListPattern = Regex("^\\s*(\\d+)[.)]\\s+(.+)$")
    val quotePattern = Regex("^\\s*>\\s?(.*)$")
    val horizontalRulePattern = Regex("^\\s*((\\*\\s*){3,}|(-\\s*){3,}|(_\\s*){3,})$")
    val openingFencePattern = Regex("^\\s*(`{3,}|~{3,})(.*)$")

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            result += MarkdownBlock.Paragraph(paragraph.joinToString("\n").trim())
            paragraph.clear()
        }
    }

    lines.forEach { line ->
        val openingFence = openingFencePattern.matchEntire(line)
        val closesCode = inCode && line.trimStart().startsWith(codeFence)
        if ((!inCode && openingFence != null) || closesCode) {
            if (inCode) {
                result += MarkdownBlock.Code(codeLanguage, code.toString().trimEnd())
                code.clear()
                codeLanguage = null
                inCode = false
            } else {
                flushParagraph()
                codeFence = openingFence!!.groupValues[1]
                codeLanguage = openingFence.groupValues[2].trim().takeIf(String::isNotBlank)
                inCode = true
            }
            return@forEach
        }
        if (inCode) {
            code.appendLine(line)
            return@forEach
        }
        val heading = headingPattern.matchEntire(line)
        val unorderedList = unorderedListPattern.matchEntire(line)
        val orderedList = orderedListPattern.matchEntire(line)
        val quote = quotePattern.matchEntire(line)
        when {
            line.isBlank() -> flushParagraph()
            horizontalRulePattern.matches(line) -> {
                flushParagraph()
                result += MarkdownBlock.HorizontalRule
            }
            heading != null -> {
                flushParagraph()
                result += MarkdownBlock.Heading(heading.groupValues[1].length, heading.groupValues[2])
            }
            unorderedList != null -> {
                flushParagraph()
                val content = unorderedList.groupValues[1]
                val checked = Regex("^\\[([ xX])]\\s+(.*)$").matchEntire(content)
                result += if (checked == null) {
                    MarkdownBlock.ListItem("•", content)
                } else {
                    MarkdownBlock.ListItem(if (checked.groupValues[1].isBlank()) "☐" else "☑", checked.groupValues[2])
                }
            }
            orderedList != null -> {
                flushParagraph()
                result += MarkdownBlock.ListItem("${orderedList.groupValues[1]}.", orderedList.groupValues[2])
            }
            quote != null -> {
                flushParagraph()
                result += MarkdownBlock.Quote(quote.groupValues[1])
            }
            else -> paragraph += line
        }
    }
    flushParagraph()
    if (inCode) result += MarkdownBlock.Code(codeLanguage, code.toString().trimEnd())
    return result
}
