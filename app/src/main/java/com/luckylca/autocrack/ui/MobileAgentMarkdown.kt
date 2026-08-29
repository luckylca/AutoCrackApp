package com.luckylca.autocrack.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

@Composable
internal fun MobileAgentMarkdown(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseMarkdownBlocks(text) }
    SelectionContainer(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            blocks.forEach { block ->
                when (block) {
                    is MarkdownBlock.Code -> MarkdownCodeBlock(block.language, block.code)
                    is MarkdownBlock.Heading -> Text(
                        text = inlineMarkdown(block.text),
                        style = when (block.level) {
                            1 -> MaterialTheme.typography.headlineSmall
                            2 -> MaterialTheme.typography.titleLarge
                            else -> MaterialTheme.typography.titleMedium
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                    is MarkdownBlock.ListItem -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("•", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(inlineMarkdown(block.text), modifier = Modifier.weight(1f))
                    }
                    is MarkdownBlock.Paragraph -> Text(inlineMarkdown(block.text))
                }
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
        val token = Regex("(`[^`]+`|\\[[^]]+\\]\\(https?://[^)]+\\)|\\*\\*[^*]+\\*\\*)")
        token.findAll(text).forEach { match ->
            if (match.range.first > cursor) append(text.substring(cursor, match.range.first))
            val raw = match.value
            when {
                raw.startsWith('`') -> {
                    val start = length
                    append(raw.removePrefix("`").removeSuffix("`"))
                    addStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground), start, length)
                }
                raw.startsWith("**") -> {
                    val start = length
                    append(raw.removePrefix("**").removeSuffix("**"))
                    addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), start, length)
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

private sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class ListItem(val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class Code(val language: String?, val code: String) : MarkdownBlock
}

private fun parseMarkdownBlocks(text: String): List<MarkdownBlock> {
    val lines = text.replace("\r\n", "\n").split('\n')
    val result = mutableListOf<MarkdownBlock>()
    val paragraph = mutableListOf<String>()
    var inCode = false
    var codeLanguage: String? = null
    val code = StringBuilder()

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            result += MarkdownBlock.Paragraph(paragraph.joinToString("\n").trim())
            paragraph.clear()
        }
    }

    lines.forEach { line ->
        if (line.startsWith("```")) {
            if (inCode) {
                result += MarkdownBlock.Code(codeLanguage, code.toString().trimEnd())
                code.clear()
                codeLanguage = null
                inCode = false
            } else {
                flushParagraph()
                codeLanguage = line.removePrefix("```").trim().takeIf(String::isNotBlank)
                inCode = true
            }
            return@forEach
        }
        if (inCode) {
            code.appendLine(line)
            return@forEach
        }
        when {
            line.isBlank() -> flushParagraph()
            line.startsWith("### ") -> { flushParagraph(); result += MarkdownBlock.Heading(3, line.removePrefix("### ")) }
            line.startsWith("## ") -> { flushParagraph(); result += MarkdownBlock.Heading(2, line.removePrefix("## ")) }
            line.startsWith("# ") -> { flushParagraph(); result += MarkdownBlock.Heading(1, line.removePrefix("# ")) }
            line.startsWith("- ") || line.startsWith("* ") -> { flushParagraph(); result += MarkdownBlock.ListItem(line.drop(2)) }
            Regex("^\\d+[.)] ").containsMatchIn(line) -> {
                flushParagraph()
                result += MarkdownBlock.ListItem(line.replaceFirst(Regex("^\\d+[.)] "), ""))
            }
            else -> paragraph += line
        }
    }
    flushParagraph()
    if (inCode) result += MarkdownBlock.Code(codeLanguage, code.toString().trimEnd())
    return result
}
