package com.luckylca.autocrack.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.luckylca.autocrack.tools.AnalysisToolExecutor
import com.luckylca.autocrack.tools.AnalysisToolId
import com.luckylca.autocrack.tools.AnalysisToolResult
import com.luckylca.autocrack.tools.LoadedToolWorkspace
import com.luckylca.autocrack.tools.WorkspaceSnapshotLoader
import kotlinx.coroutines.launch

private sealed interface ToolWorkspaceState {
    data object Loading : ToolWorkspaceState
    data class Ready(val workspace: LoadedToolWorkspace) : ToolWorkspaceState
    data class Error(val message: String) : ToolWorkspaceState
}

@Composable
fun AnalysisToolsScreen() {
    val context = LocalContext.current
    val loader = remember(context) { WorkspaceSnapshotLoader(context.applicationContext) }
    val executor = remember { AnalysisToolExecutor() }
    val scope = rememberCoroutineScope()
    var refreshKey by remember { mutableIntStateOf(0) }
    var workspaceState by remember { mutableStateOf<ToolWorkspaceState>(ToolWorkspaceState.Loading) }
    var runningTool by remember { mutableStateOf<AnalysisToolId?>(null) }
    var lastResult by remember { mutableStateOf<AnalysisToolResult?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var dexQuery by remember { mutableStateOf("") }
    var elfQuery by remember { mutableStateOf("") }

    LaunchedEffect(refreshKey) {
        workspaceState = ToolWorkspaceState.Loading
        errorMessage = null
        workspaceState = runCatching { loader.loadLatest() }
            .fold(
                onSuccess = ToolWorkspaceState::Ready,
                onFailure = { exception ->
                    ToolWorkspaceState.Error(exception.message ?: "读取工具工作区失败")
                },
            )
    }

    fun runTool(toolId: AnalysisToolId, input: String = "") {
        val workspace = (workspaceState as? ToolWorkspaceState.Ready)?.workspace ?: return
        scope.launch {
            runningTool = toolId
            errorMessage = null
            try {
                lastResult = executor.execute(
                    toolId = toolId,
                    input = input,
                    extraction = workspace.extraction,
                    staticReport = workspace.staticReport,
                    dexIndex = workspace.dexIndex,
                )
            } catch (exception: Exception) {
                errorMessage = exception.message ?: "工具执行失败"
            } finally {
                runningTool = null
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "分析工具箱",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "先把工具做实，再让 Agent 编排工具。当前工具全部为只读。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        when (val state = workspaceState) {
            ToolWorkspaceState.Loading -> item {
                ToolCard("读取工作区") {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("正在查找最近一次已完成的静态分析和 DEX 索引")
                }
            }

            is ToolWorkspaceState.Error -> item {
                ToolCard("没有可用工作区") {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { refreshKey += 1 },
                    ) {
                        Text("重新读取")
                    }
                }
            }

            is ToolWorkspaceState.Ready -> {
                val workspace = state.workspace
                item {
                    ToolCard("当前工具工作区") {
                        ToolInfoRow("包名", workspace.extraction.packageName)
                        ToolInfoRow("目录", workspace.extraction.workspacePath)
                        ToolInfoRow(
                            "规模",
                            "DEX ${workspace.staticReport.dexFileCount} / SO ${workspace.staticReport.nativeLibraryCount}",
                        )
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { refreshKey += 1 },
                            enabled = runningTool == null,
                        ) {
                            Text("刷新最近工作区")
                        }
                    }
                }

                item {
                    SimpleToolCard(
                        toolId = AnalysisToolId.APK_OVERVIEW,
                        runningTool = runningTool,
                        onRun = { runTool(AnalysisToolId.APK_OVERVIEW) },
                    )
                }
                item {
                    SimpleToolCard(
                        toolId = AnalysisToolId.DEX_OVERVIEW,
                        runningTool = runningTool,
                        onRun = { runTool(AnalysisToolId.DEX_OVERVIEW) },
                    )
                }
                item {
                    ToolCard(AnalysisToolId.DEX_SEARCH.title) {
                        Text(AnalysisToolId.DEX_SEARCH.description)
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = dexQuery,
                            onValueChange = { dexQuery = it },
                            label = { Text("包名、类名、方法名或字符串") },
                            singleLine = true,
                        )
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { runTool(AnalysisToolId.DEX_SEARCH, dexQuery) },
                            enabled = runningTool == null && dexQuery.trim().length >= 2,
                        ) {
                            Text(if (runningTool == AnalysisToolId.DEX_SEARCH) "搜索中" else "运行 DEX 搜索")
                        }
                    }
                }
                item {
                    SimpleToolCard(
                        toolId = AnalysisToolId.DEX_NATIVE_METHODS,
                        runningTool = runningTool,
                        onRun = { runTool(AnalysisToolId.DEX_NATIVE_METHODS) },
                    )
                }
                item {
                    SimpleToolCard(
                        toolId = AnalysisToolId.SO_OVERVIEW,
                        runningTool = runningTool,
                        onRun = { runTool(AnalysisToolId.SO_OVERVIEW) },
                    )
                }
                item {
                    ToolCard(AnalysisToolId.ELF_INSPECT.title) {
                        Text(AnalysisToolId.ELF_INSPECT.description)
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = elfQuery,
                            onValueChange = { elfQuery = it },
                            label = { Text("SO 文件名或完整 entry") },
                            supportingText = { Text("例如 libfoo.so 或 lib/arm64-v8a/libfoo.so") },
                            singleLine = true,
                        )
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { runTool(AnalysisToolId.ELF_INSPECT, elfQuery) },
                            enabled = runningTool == null && elfQuery.trim().length >= 3,
                        ) {
                            Text(if (runningTool == AnalysisToolId.ELF_INSPECT) "解析中" else "深度分析 ELF")
                        }
                        Text("可用 SO", fontWeight = FontWeight.SemiBold)
                        workspace.staticReport.archives
                            .flatMap { archive -> archive.nativeLibraries }
                            .take(MAX_VISIBLE_SO_CHOICES)
                            .forEach { library ->
                                OutlinedButton(
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = { elfQuery = library.entryName },
                                    enabled = runningTool == null,
                                ) {
                                    Text(
                                        text = "${library.abi} · ${library.fileName}",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                    }
                }
            }
        }

        runningTool?.let { toolId ->
            item {
                ToolCard("工具执行中") {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(toolId.title)
                }
            }
        }

        errorMessage?.let { message ->
            item {
                ToolCard("工具执行失败") {
                    Text(message, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        lastResult?.let { result ->
            item {
                ToolCard(result.title) {
                    ToolInfoRow("风险等级", result.risk.name)
                    ToolInfoRow("耗时", "${result.durationMillis} ms")
                    Text(result.summary, fontWeight = FontWeight.SemiBold)
                    result.details.forEach { line ->
                        HorizontalDivider()
                        Text(line, style = MaterialTheme.typography.bodySmall)
                    }
                    ToolInfoRow("完整 JSON", result.outputFilePath)
                }
            }
        }

        item { Spacer(Modifier.height(88.dp)) }
    }
}

@Composable
private fun SimpleToolCard(
    toolId: AnalysisToolId,
    runningTool: AnalysisToolId?,
    onRun: () -> Unit,
) {
    ToolCard(toolId.title) {
        Text(toolId.description)
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = onRun,
            enabled = runningTool == null,
        ) {
            Text(if (runningTool == toolId) "执行中" else "运行")
        }
    }
}

@Composable
private fun ToolCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun ToolInfoRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private const val MAX_VISIBLE_SO_CHOICES = 30
