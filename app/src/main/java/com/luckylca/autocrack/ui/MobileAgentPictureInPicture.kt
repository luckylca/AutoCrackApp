package com.luckylca.autocrack.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.luckylca.autocrack.agent.MobileAgentTaskSnapshot
import com.luckylca.autocrack.agent.MobileAgentTaskStatus

internal data class MobileAgentPictureInPictureState(
    val conversationId: String,
    val stage: String,
    val output: String,
    val isRunning: Boolean,
)

internal fun nextPictureInPictureState(
    tasks: Map<String, MobileAgentTaskSnapshot>,
    previous: MobileAgentPictureInPictureState?,
): MobileAgentPictureInPictureState? {
    val running = tasks.values
        .asSequence()
        .filter { it.status == MobileAgentTaskStatus.RUNNING }
        .maxByOrNull(MobileAgentTaskSnapshot::updatedAtEpochMillis)
    if (running != null) {
        val previousOutput = previous
            ?.takeIf { it.conversationId == running.conversationId }
            ?.output
            .orEmpty()
        return MobileAgentPictureInPictureState(
            conversationId = running.conversationId,
            stage = running.stage,
            output = running.streamingText.ifBlank { previousOutput },
            isRunning = true,
        )
    }

    previous ?: return null
    val finishedTask = tasks[previous.conversationId]
    return previous.copy(
        stage = finishedTask?.stage ?: previous.stage,
        isRunning = false,
    )
}

@Composable
internal fun MobileAgentPictureInPictureContent(state: MobileAgentPictureInPictureState) {
    val output = remember(state.output) { pictureInPictureOutputExcerpt(state.output) }
    val outputScrollState = rememberScrollState()
    LaunchedEffect(output, outputScrollState.maxValue) {
        outputScrollState.scrollTo(outputScrollState.maxValue)
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (state.isRunning) "●" else "✓",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                text = state.stage.ifBlank { if (state.isRunning) "模型正在输出" else "回答已完成" },
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            text = output.ifBlank { "等待模型输出…" },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(outputScrollState),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

internal fun pictureInPictureOutputExcerpt(text: String, maxChars: Int = 700): String {
    val normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim()
    if (normalized.isEmpty()) return ""
    val tail = normalized.takeLast(maxChars)
    val startsMidOutput = tail.length < normalized.length
    val cleaned = tail
        .lineSequence()
        .filterNot { it.trimStart().startsWith("```") || it.trimStart().startsWith("~~~") }
        .joinToString("\n") { line ->
            line.replaceFirst(Regex("^\\s*(?:#{1,6}|>|[-+*]|\\d+[.)])\\s+"), "")
        }
        .replace(Regex("(`|\\*\\*|__|~~)"), "")
        .trimStart()
    return if (startsMidOutput) "…$cleaned" else cleaned
}
