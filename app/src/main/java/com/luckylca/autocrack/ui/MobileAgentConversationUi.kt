package com.luckylca.autocrack.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.luckylca.autocrack.agent.MobileAgentAttachment
import com.luckylca.autocrack.agent.MobileAgentConversation
import com.luckylca.autocrack.agent.MobileAgentMessage
import com.luckylca.autocrack.agent.MobileAgentRole
import com.luckylca.autocrack.agent.MobileAgentTaskSnapshot
import com.luckylca.autocrack.agent.MobileAgentTaskStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

internal enum class AgentAttachmentKind { FILE, APK, IMAGE }

internal data class AgentManagedFile(
    val displayName: String,
    val relativePath: String,
    val mimeType: String?,
    val sizeBytes: Long,
)

@Composable
internal fun MobileAgentConversationPage(
    conversations: List<MobileAgentConversation>,
    activeConversation: MobileAgentConversation?,
    input: String,
    onInputChange: (String) -> Unit,
    pendingAttachments: List<MobileAgentAttachment>,
    searchQuery: String,
    searchMatchIds: Set<String>?,
    onSearchChange: (String) -> Unit,
    taskForConversation: (String) -> MobileAgentTaskSnapshot?,
    uiStatus: String?,
    hasApi: Boolean,
    onNewConversation: () -> Unit,
    onOpenConversation: (MobileAgentConversation) -> Unit,
    onAttach: (AgentAttachmentKind) -> Unit,
    onRemoveAttachment: (MobileAgentAttachment) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onRename: (MobileAgentConversation) -> Unit,
    onDelete: (MobileAgentConversation) -> Unit,
    onOpenApiSettings: () -> Unit,
    onOpenFile: (AgentManagedFile) -> Unit,
    onShareFile: (AgentManagedFile) -> Unit,
    onSaveFile: (AgentManagedFile) -> Unit,
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.fillMaxWidth(0.88f)) {
                ConversationDrawer(
                    conversations = conversations,
                    activeConversationId = activeConversation?.id,
                    searchQuery = searchQuery,
                    searchMatchIds = searchMatchIds,
                    onSearchChange = onSearchChange,
                    taskForConversation = taskForConversation,
                    onNewConversation = {
                        onNewConversation()
                        scope.launch { drawerState.close() }
                    },
                    onOpenConversation = {
                        onOpenConversation(it)
                        scope.launch { drawerState.close() }
                    },
                    onRename = onRename,
                    onDelete = onDelete,
                )
            }
        },
    ) {
        ConversationBody(
            activeConversation = activeConversation,
            input = input,
            onInputChange = onInputChange,
            pendingAttachments = pendingAttachments,
            task = activeConversation?.id?.let(taskForConversation),
            uiStatus = uiStatus,
            hasApi = hasApi,
            onOpenDrawer = { scope.launch { drawerState.open() } },
            onNewConversation = onNewConversation,
            onAttach = onAttach,
            onRemoveAttachment = onRemoveAttachment,
            onSend = onSend,
            onStop = onStop,
            onOpenApiSettings = onOpenApiSettings,
            onOpenFile = onOpenFile,
            onShareFile = onShareFile,
            onSaveFile = onSaveFile,
        )
    }
}

@Composable
private fun ConversationBody(
    activeConversation: MobileAgentConversation?,
    input: String,
    onInputChange: (String) -> Unit,
    pendingAttachments: List<MobileAgentAttachment>,
    task: MobileAgentTaskSnapshot?,
    uiStatus: String?,
    hasApi: Boolean,
    onOpenDrawer: () -> Unit,
    onNewConversation: () -> Unit,
    onAttach: (AgentAttachmentKind) -> Unit,
    onRemoveAttachment: (MobileAgentAttachment) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onOpenApiSettings: () -> Unit,
    onOpenFile: (AgentManagedFile) -> Unit,
    onShareFile: (AgentManagedFile) -> Unit,
    onSaveFile: (AgentManagedFile) -> Unit,
) {
    val running = task?.status == MobileAgentTaskStatus.RUNNING
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onOpenDrawer) { Text("☰", style = MaterialTheme.typography.titleLarge) }
            Text(
                text = activeConversation?.title ?: "新会话",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onNewConversation) { Text("＋", style = MaterialTheme.typography.titleLarge) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

        if (activeConversation == null || activeConversation.messages.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(
                    modifier = Modifier.padding(horizontal = 30.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("有什么需要我完成的？", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text("直接描述目标，Agent 会自行使用当前环境和已安装工具。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            val renderItems = remember(activeConversation.messages, running) { buildConversationItems(activeConversation.messages, running) }
            val listState = rememberLazyListState()
            val streamingText = task?.streamingText.orEmpty().takeIf { running && it.isNotBlank() }
            val showTerminalStatus = !running && task != null &&
                task.status in setOf(MobileAgentTaskStatus.FAILED, MobileAgentTaskStatus.CANCELLED, MobileAgentTaskStatus.INTERRUPTED)
            val lastItemIndex = renderItems.size + (if (streamingText != null) 1 else 0) +
                (if (showTerminalStatus) 1 else 0) - 1
            val streamingScrollTick = streamingText?.length?.div(STREAMING_SCROLL_CHAR_STEP) ?: 0
            LaunchedEffect(
                activeConversation.id,
                renderItems.size,
                renderItems.lastOrNull()?.key,
                streamingScrollTick,
                showTerminalStatus,
            ) {
                if (lastItemIndex >= 0) listState.scrollToItem(lastItemIndex)
            }
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(renderItems, key = { it.key }) { item ->
                    when (item) {
                        is ConversationItem.User -> UserMessage(item.message, onOpenFile, onShareFile, onSaveFile)
                        is ConversationItem.Assistant -> AssistantMessage(item.text)
                        is ConversationItem.ToolCall -> ToolCallCard(item.call, onOpenFile, onShareFile, onSaveFile)
                    }
                }
                streamingText?.let { streaming ->
                    item(key = "streaming") { AssistantMessage(streaming, streaming = true) }
                }
                if (showTerminalStatus) {
                    item(key = "terminal-status") {
                        Text(
                            listOfNotNull(task.stage, task.error).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (task.status == MobileAgentTaskStatus.CANCELLED) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
            if (running) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("●", color = MaterialTheme.colorScheme.primary)
                    Text(
                        task.stage.ifBlank { "Agent 正在工作" },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = onStop) { Text("停止") }
                }
            }
            uiStatus?.let { Text(it, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (!hasApi) TextButton(modifier = Modifier.fillMaxWidth(), onClick = onOpenApiSettings) { Text("配置 API 后开始") }
            pendingAttachments.forEach { attachment -> PendingAttachmentRow(attachment, !running, onRemoveAttachment) }
            Composer(
                value = input,
                onValueChange = onInputChange,
                enabled = !running,
                canSend = hasApi && (input.isNotBlank() || pendingAttachments.isNotEmpty()),
                running = running,
                onAttach = onAttach,
                onSend = onSend,
                onStop = onStop,
            )
        }
    }
}

@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    canSend: Boolean,
    running: Boolean,
    onAttach: (AgentAttachmentKind) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    var attachmentMenu by remember { mutableStateOf(false) }
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), tonalElevation = 2.dp, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.Bottom) {
            Box {
                TextButton(onClick = { attachmentMenu = true }, enabled = enabled) { Text("＋", style = MaterialTheme.typography.titleLarge) }
                DropdownMenu(expanded = attachmentMenu, onDismissRequest = { attachmentMenu = false }) {
                    DropdownMenuItem(text = { Text("选择文件") }, onClick = { attachmentMenu = false; onAttach(AgentAttachmentKind.FILE) })
                    DropdownMenuItem(text = { Text("选择 APK") }, onClick = { attachmentMenu = false; onAttach(AgentAttachmentKind.APK) })
                    DropdownMenuItem(text = { Text("选择图片") }, onClick = { attachmentMenu = false; onAttach(AgentAttachmentKind.IMAGE) })
                }
            }
            OutlinedTextField(
                modifier = Modifier.weight(1f), value = value, onValueChange = onValueChange,
                placeholder = { Text("输入消息……") }, minLines = 1, maxLines = 5, enabled = enabled, shape = RoundedCornerShape(20.dp),
            )
            TextButton(onClick = if (running) onStop else onSend, enabled = running || canSend) {
                Text(if (running) "■" else "↑", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

@Composable
private fun UserMessage(
    message: MobileAgentMessage,
    onOpenFile: (AgentManagedFile) -> Unit,
    onShareFile: (AgentManagedFile) -> Unit,
    onSaveFile: (AgentManagedFile) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (message.content.isNotBlank()) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.84f),
                shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) { Text(message.content, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) }
        }
        message.attachments.forEach { attachment ->
            AgentFileCard(
                file = AgentManagedFile(attachment.displayName, attachment.relativePath, attachment.mimeType, attachment.sizeBytes),
                compact = true, onOpen = onOpenFile, onShare = onShareFile, onSave = onSaveFile,
            )
        }
    }
}

@Composable
private fun AssistantMessage(text: String, streaming: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Text("●", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 3.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (streaming) Text("Working", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            MobileAgentMarkdown(text)
        }
    }
}

@Composable
private fun ToolCallCard(
    call: ToolCallPresentation,
    onOpenFile: (AgentManagedFile) -> Unit,
    onShareFile: (AgentManagedFile) -> Unit,
    onSaveFile: (AgentManagedFile) -> Unit,
) {
    var expanded by remember(call.callId) { mutableStateOf(false) }
    val statusSymbol = when (call.status) { ToolVisualStatus.RUNNING -> "●"; ToolVisualStatus.SUCCESS -> "✓"; ToolVisualStatus.FAILED -> "!" }
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(statusSymbol, color = when (call.status) {
                    ToolVisualStatus.RUNNING -> MaterialTheme.colorScheme.primary
                    ToolVisualStatus.SUCCESS -> MaterialTheme.colorScheme.onSurfaceVariant
                    ToolVisualStatus.FAILED -> MaterialTheme.colorScheme.error
                })
                Text(call.displayTool, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Text(call.durationMillis?.let(::formatDuration).orEmpty(), modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (expanded) "⌃" else "⌄", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                call.summary, maxLines = if (expanded) 4 else 2, overflow = TextOverflow.Ellipsis,
                fontFamily = if (call.toolName == "exec_bash") FontFamily.Monospace else null,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ToolDetail(call)
            }
        }
    }
    call.generatedFile?.let { file ->
        Spacer(Modifier.height(6.dp))
        AgentFileCard(file, false, onOpenFile, onShareFile, onSaveFile)
    }
}

@Composable
private fun ToolDetail(call: ToolCallPresentation) {
    val context = LocalContext.current
    val output = remember(call.resultContent) { parseToolOutput(call.resultContent) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(call.displayTool, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(call.command, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        output.stdout.takeIf(String::isNotBlank)?.let { ToolLogBlock("stdout", it) }
        output.stderr.takeIf(String::isNotBlank)?.let { ToolLogBlock("stderr", it, error = true) }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("exit code: ${call.exitCode ?: "—"}", style = MaterialTheme.typography.labelSmall)
            call.durationMillis?.let { Text(formatDuration(it), style = MaterialTheme.typography.labelSmall) }
            TextButton(onClick = {
                val complete = buildString {
                    appendLine(call.command)
                    if (output.stdout.isNotBlank()) appendLine("\nstdout:\n${output.stdout}")
                    if (output.stderr.isNotBlank()) appendLine("\nstderr:\n${output.stderr}")
                }
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Tool call", complete))
                Toast.makeText(context, "Tool 日志已复制", Toast.LENGTH_SHORT).show()
            }) { Text("复制") }
        }
    }
}

@Composable
private fun ToolLogBlock(label: String, value: String, error: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(8.dp)).verticalScroll(rememberScrollState()).padding(8.dp),
            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
internal fun AgentFileCard(
    file: AgentManagedFile,
    compact: Boolean,
    onOpen: (AgentManagedFile) -> Unit,
    onShare: (AgentManagedFile) -> Unit,
    onSave: (AgentManagedFile) -> Unit,
) {
    Surface(modifier = Modifier.widthIn(max = if (compact) 340.dp else 480.dp), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(fileKind(file.displayName, file.mimeType), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(file.displayName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(formatAgentBytes(file.sizeBytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onOpen(file) }) { Text("打开") }
                TextButton(onClick = { onShare(file) }) { Text("分享") }
                TextButton(onClick = { onSave(file) }) { Text("保存") }
            }
        }
    }
}

@Composable
private fun PendingAttachmentRow(attachment: MobileAgentAttachment, enabled: Boolean, onRemove: (MobileAgentAttachment) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${attachment.displayName} · ${formatAgentBytes(attachment.sizeBytes)}", modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { onRemove(attachment) }, enabled = enabled) { Text("移除") }
    }
}

@Composable
private fun ConversationDrawer(
    conversations: List<MobileAgentConversation>,
    activeConversationId: String?,
    searchQuery: String,
    searchMatchIds: Set<String>?,
    onSearchChange: (String) -> Unit,
    taskForConversation: (String) -> MobileAgentTaskSnapshot?,
    onNewConversation: () -> Unit,
    onOpenConversation: (MobileAgentConversation) -> Unit,
    onRename: (MobileAgentConversation) -> Unit,
    onDelete: (MobileAgentConversation) -> Unit,
) {
    Column(modifier = Modifier.fillMaxHeight().padding(horizontal = 12.dp, vertical = 12.dp)) {
        Button(modifier = Modifier.fillMaxWidth(), onClick = onNewConversation) { Text("＋ 新建会话") }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(modifier = Modifier.fillMaxWidth(), value = searchQuery, onValueChange = onSearchChange, placeholder = { Text("搜索会话") }, singleLine = true)
        Spacer(Modifier.height(10.dp))
        val query = searchQuery.trim()
        val filtered = conversations.filter { conversation ->
            query.isBlank() || conversation.title.contains(query, true) || conversation.id in searchMatchIds.orEmpty()
        }
        val grouped = remember(filtered) { groupConversations(filtered) }
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            grouped.forEach { (label, entries) ->
                item(key = "group-$label") { Text(label, modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(entries, key = MobileAgentConversation::id) { conversation ->
                    val running = taskForConversation(conversation.id)?.status == MobileAgentTaskStatus.RUNNING
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { onOpenConversation(conversation) },
                        shape = RoundedCornerShape(10.dp),
                        color = if (conversation.id == activeConversationId) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(conversation.title, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                if (running) Text("●", color = MaterialTheme.colorScheme.primary)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(formatConversationTime(conversation.updatedAtEpochMillis), modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = { onRename(conversation) }) { Text("重命名", style = MaterialTheme.typography.labelSmall) }
                                TextButton(onClick = { onDelete(conversation) }, enabled = !running) { Text("删除", style = MaterialTheme.typography.labelSmall) }
                            }
                        }
                    }
                }
            }
        }
    }
}

private sealed interface ConversationItem {
    val key: String
    data class User(val message: MobileAgentMessage) : ConversationItem { override val key: String = message.id }
    data class Assistant(override val key: String, val text: String) : ConversationItem
    data class ToolCall(override val key: String, val call: ToolCallPresentation) : ConversationItem
}

private enum class ToolVisualStatus { RUNNING, SUCCESS, FAILED }

private data class ToolCallPresentation(
    val callId: String,
    val toolName: String,
    val displayTool: String,
    val command: String,
    val summary: String,
    val status: ToolVisualStatus,
    val resultContent: String?,
    val exitCode: Int?,
    val durationMillis: Long?,
    val generatedFile: AgentManagedFile? = null,
)

private data class ToolOutput(val stdout: String, val stderr: String)

private fun buildConversationItems(messages: List<MobileAgentMessage>, taskRunning: Boolean): List<ConversationItem> {
    val toolResults = messages.filter { it.role == MobileAgentRole.TOOL && it.toolCallId != null }.associateBy { it.toolCallId.orEmpty() }
    return buildList {
        messages.forEach { message ->
            when (message.role) {
                MobileAgentRole.USER -> add(ConversationItem.User(message))
                MobileAgentRole.TOOL -> Unit
                MobileAgentRole.ASSISTANT -> {
                    if (message.content.isNotBlank()) add(ConversationItem.Assistant("${message.id}-text", message.content))
                    message.toolCallsJson?.let { raw ->
                        val calls = runCatching { JSONArray(raw) }.getOrNull() ?: return@let
                        for (index in 0 until calls.length()) {
                            val call = calls.optJSONObject(index) ?: continue
                            val id = call.optString("id")
                            val function = call.optJSONObject("function") ?: continue
                            val name = function.optString("name")
                            val args = runCatching { JSONObject(function.optString("arguments", "{}")) }.getOrDefault(JSONObject())
                            add(ConversationItem.ToolCall("$id-tool", buildToolPresentation(id, name, args, toolResults[id], taskRunning)))
                        }
                    }
                }
            }
        }
    }
}

private fun buildToolPresentation(callId: String, toolName: String, args: JSONObject, resultMessage: MobileAgentMessage?, taskRunning: Boolean): ToolCallPresentation {
    val result = resultMessage?.content?.let { runCatching { JSONObject(it) }.getOrNull() }
    val status = when {
        result == null && taskRunning -> ToolVisualStatus.RUNNING
        result?.optBoolean("ok", false) == true -> ToolVisualStatus.SUCCESS
        else -> ToolVisualStatus.FAILED
    }
    val command = when (toolName) {
        "exec_bash" -> args.optString("script")
        "read_file" -> "read ${args.optString("path")}".trim()
        "write_file" -> "write ${args.optString("path")}".trim()
        "kill_process" -> "kill ${args.optString("signal", "TERM")} ${args.optInt("pid", -1)}"
        else -> args.toString()
    }.ifBlank { toolName }
    val generated = if (toolName == "write_file" && result?.optBoolean("ok", false) == true) {
        result.optJSONObject("entry")?.let { entry ->
            val path = entry.optString("relativePath")
            if (path.isBlank()) null else AgentManagedFile(entry.optString("name").ifBlank { path.substringAfterLast('/') }, path, null, entry.optLong("sizeBytes"))
        }
    } else null
    return ToolCallPresentation(
        callId = callId,
        toolName = toolName,
        displayTool = when (toolName) { "exec_bash" -> "bash"; "read_file" -> "read"; "write_file" -> "write"; "kill_process" -> "kill"; else -> toolName },
        command = command,
        summary = command.lineSequence().take(2).joinToString("\n").take(280),
        status = status,
        resultContent = resultMessage?.content,
        exitCode = result?.takeIf { it.has("exitCode") && !it.isNull("exitCode") }?.optInt("exitCode"),
        durationMillis = result?.takeIf { it.has("durationMillis") }?.optLong("durationMillis"),
        generatedFile = generated,
    )
}

private fun parseToolOutput(resultContent: String?): ToolOutput {
    val result = resultContent?.let { runCatching { JSONObject(it) }.getOrNull() }
    return ToolOutput(
        stdout = result?.optString("stdout").orEmpty().take(MAX_TOOL_LOG_CHARS),
        stderr = result?.optString("stderr").orEmpty()
            .ifBlank { result?.optString("error").orEmpty() }
            .take(MAX_TOOL_LOG_CHARS),
    )
}

private const val STREAMING_SCROLL_CHAR_STEP = 192
private const val MAX_TOOL_LOG_CHARS = 40_000

private fun groupConversations(conversations: List<MobileAgentConversation>): List<Pair<String, List<MobileAgentConversation>>> {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val yesterday = today.minusDays(1)
    val groups = linkedMapOf("今天" to mutableListOf<MobileAgentConversation>(), "昨天" to mutableListOf(), "更早" to mutableListOf())
    conversations.sortedByDescending { it.updatedAtEpochMillis }.forEach { conversation ->
        val date = Instant.ofEpochMilli(conversation.updatedAtEpochMillis).atZone(zone).toLocalDate()
        when (date) { today -> groups.getValue("今天") += conversation; yesterday -> groups.getValue("昨天") += conversation; else -> groups.getValue("更早") += conversation }
    }
    return groups.filterValues { it.isNotEmpty() }.map { it.key to it.value }
}

private fun formatConversationTime(epochMillis: Long): String = runCatching {
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
}.getOrDefault("")

private fun fileKind(name: String, mime: String?): String = when {
    name.endsWith(".apk", true) -> "APK"
    name.endsWith(".js", true) -> "JS"
    name.endsWith(".py", true) -> "PYTHON"
    name.endsWith(".zip", true) -> "ZIP"
    mime?.startsWith("image/") == true -> "IMAGE"
    else -> name.substringAfterLast('.', "FILE").uppercase().take(10)
}

internal fun formatAgentBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private fun formatDuration(millis: Long): String = when {
    millis < 1_000 -> "${millis}ms"
    millis < 60_000 -> "%.1fs".format(millis / 1000.0)
    else -> "%.1fmin".format(millis / 60_000.0)
}
