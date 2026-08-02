package com.luckylca.autocrack.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.luckylca.autocrack.BuildConfig
import com.luckylca.autocrack.runtime.ChrootPtySessionManager
import com.luckylca.autocrack.runtime.DEFAULT_TERMINAL_COLUMNS
import com.luckylca.autocrack.runtime.DEFAULT_TERMINAL_ROWS
import com.luckylca.autocrack.runtime.PtySessionState
import com.luckylca.autocrack.runtime.RuntimeLayout
import com.luckylca.autocrack.runtime.RuntimeRootfsState
import kotlinx.coroutines.launch

@Composable
fun PtyTerminalScreen() {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val manager = remember(appContext) { ChrootPtySessionManager.get(appContext) }
    val layout = remember(appContext) { RuntimeLayout(appContext).initialize() }
    val snapshot by manager.snapshot.collectAsState()
    val scope = rememberCoroutineScope()
    val terminalScroll = rememberScrollState()
    val quickScroll = rememberScrollState()

    var input by remember { mutableStateOf("") }
    var rowsText by remember { mutableStateOf(DEFAULT_TERMINAL_ROWS.toString()) }
    var columnsText by remember { mutableStateOf(DEFAULT_TERMINAL_COLUMNS.toString()) }
    var actionStatus by remember { mutableStateOf("等待打开 Debian PTY") }

    LaunchedEffect(snapshot.outputVersion) {
        terminalScroll.scrollTo(terminalScroll.maxValue)
    }

    fun launchAction(action: suspend () -> Unit) {
        scope.launch {
            runCatching { action() }
                .onSuccess { actionStatus = "操作完成" }
                .onFailure { exception ->
                    actionStatus = exception.message ?: exception::class.java.name
                }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        Text(
            "Debian PTY Terminal",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "${BuildConfig.VERSION_NAME} · Native JNI PTY · KernelSU chroot",
            color = MaterialTheme.colorScheme.primary,
        )

        TerminalCard("会话") {
            TerminalInfoRow("Rootfs", "${layout.readRootfsState()} / ${layout.readRootfsVersion() ?: "无版本"}")
            TerminalInfoRow("状态", snapshot.state.name)
            TerminalInfoRow("会话 ID", snapshot.sessionId ?: "无")
            TerminalInfoRow("PID", snapshot.pid?.toString() ?: "无")
            TerminalInfoRow("终端尺寸", "${snapshot.rows} x ${snapshot.columns}")
            TerminalInfoRow("读取 / 写入", "${snapshot.bytesRead} B / ${snapshot.bytesWritten} B")
            snapshot.failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (snapshot.state == PtySessionState.STARTING || snapshot.state == PtySessionState.CLOSING) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    modifier = Modifier.weight(1f),
                    value = rowsText,
                    onValueChange = { rowsText = it.filter(Char::isDigit) },
                    label = { Text("行") },
                    singleLine = true,
                )
                OutlinedTextField(
                    modifier = Modifier.weight(1f),
                    value = columnsText,
                    onValueChange = { columnsText = it.filter(Char::isDigit) },
                    label = { Text("列") },
                    singleLine = true,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = !snapshot.isRunning &&
                        layout.readRootfsState() == RuntimeRootfsState.INSTALLED,
                    onClick = {
                        launchAction {
                            val rows = rowsText.toIntOrNull() ?: error("行数非法")
                            val columns = columnsText.toIntOrNull() ?: error("列数非法")
                            manager.open(rows, columns)
                            actionStatus = "PTY 已打开"
                        }
                    },
                ) {
                    Text("打开 PTY")
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = snapshot.isRunning,
                    onClick = {
                        launchAction {
                            manager.close()
                            actionStatus = "PTY 已关闭"
                        }
                    },
                ) {
                    Text("关闭")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = snapshot.state == PtySessionState.RUNNING,
                    onClick = {
                        launchAction {
                            check(manager.interrupt()) { "Ctrl+C 写入失败" }
                            actionStatus = "已发送 Ctrl+C"
                        }
                    },
                ) {
                    Text("Ctrl+C")
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = snapshot.state == PtySessionState.RUNNING,
                    onClick = {
                        launchAction {
                            val rows = rowsText.toIntOrNull() ?: error("行数非法")
                            val columns = columnsText.toIntOrNull() ?: error("列数非法")
                            check(manager.resize(rows, columns)) { "PTY resize 失败" }
                            actionStatus = "窗口已调整"
                        }
                    },
                ) {
                    Text("Resize")
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = manager::clearOutput,
                ) {
                    Text("清屏")
                }
            }
            Text(actionStatus, style = MaterialTheme.typography.bodySmall)
        }

        TerminalCard("终端输出") {
            Text(
                text = if (snapshot.output.isBlank()) "尚无输出" else snapshot.output,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 280.dp, max = 520.dp)
                    .verticalScroll(terminalScroll)
                    .horizontalScroll(rememberScrollState())
                    .padding(8.dp),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        TerminalCard("输入") {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = input,
                onValueChange = { input = it },
                label = { Text("发送到 Bash") },
                minLines = 2,
                maxLines = 5,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                enabled = snapshot.state == PtySessionState.RUNNING,
            )
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = snapshot.state == PtySessionState.RUNNING && input.isNotBlank(),
                onClick = {
                    val command = input
                    input = ""
                    launchAction {
                        manager.sendLine(command)
                        actionStatus = "命令已发送"
                    }
                },
            ) {
                Text("发送并回车")
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(quickScroll),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QUICK_COMMANDS.forEach { command ->
                    Text(
                        text = command,
                        modifier = Modifier
                            .selectable(
                                selected = false,
                                enabled = snapshot.state == PtySessionState.RUNNING,
                                onClick = {
                                    launchAction {
                                        manager.sendLine(command)
                                        actionStatus = "已发送：$command"
                                    }
                                },
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.primary,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }

        TerminalCard("Transcript 与诊断") {
            TerminalInfoRow("Transcript", snapshot.transcriptPath ?: "无")
            TerminalInfoRow("审计", snapshot.auditPath ?: "无")
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val report = manager.diagnostics()
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                        ClipData.newPlainText("AutoCrackApp Native PTY 诊断", report),
                    )
                    Toast.makeText(context, "PTY 诊断已复制", Toast.LENGTH_SHORT).show()
                },
            ) {
                Text("复制完整 PTY 诊断")
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun TerminalCard(title: String, content: @Composable () -> Unit) {
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
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun TerminalInfoRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, fontFamily = FontFamily.Monospace)
    }
}

private val QUICK_COMMANDS = listOf(
    "pwd",
    "id",
    "uname -a",
    "ls -la",
    "python3 --version",
    "readelf --version | head -1",
    "sleep 30",
    "exit",
)
