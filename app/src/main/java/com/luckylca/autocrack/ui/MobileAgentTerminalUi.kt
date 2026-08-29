package com.luckylca.autocrack.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.luckylca.autocrack.runtime.ChrootPtySessionManager
import com.luckylca.autocrack.runtime.DEFAULT_TERMINAL_COLUMNS
import com.luckylca.autocrack.runtime.DEFAULT_TERMINAL_ROWS
import com.luckylca.autocrack.runtime.PtySessionState
import kotlinx.coroutines.launch

@Composable
internal fun MobileAgentTerminalPage(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val manager = remember(context) { ChrootPtySessionManager.get(context.applicationContext) }
    val snapshot by manager.snapshot.collectAsState()
    val scope = rememberCoroutineScope()
    val outputScroll = rememberScrollState()
    var input by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(snapshot.outputVersion) {
        outputScroll.scrollTo(outputScroll.maxValue)
    }

    fun runAction(action: suspend () -> Unit) {
        scope.launch {
            runCatching { action() }
                .onFailure { status = it.message ?: it::class.java.simpleName }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        MobileAgentSettingsHeader(title = "Debian Terminal", onBack = onBack)
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = !snapshot.isRunning,
                    onClick = {
                        runAction {
                            manager.open(DEFAULT_TERMINAL_ROWS, DEFAULT_TERMINAL_COLUMNS)
                            status = "Debian PTY 已打开"
                        }
                    },
                ) { Text("打开") }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = snapshot.isRunning,
                    onClick = { runAction { manager.close(); status = "Debian PTY 已关闭" } },
                ) { Text("关闭") }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = snapshot.state == PtySessionState.RUNNING,
                    onClick = { runAction { check(manager.interrupt()) { "发送 Ctrl+C 失败" } } },
                ) { Text("Ctrl+C") }
            }
            Text(
                "${snapshot.state.name}${snapshot.pid?.let { " · PID $it" }.orEmpty()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(
                text = snapshot.output.ifBlank { "尚无输出" },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(10.dp))
                    .verticalScroll(outputScroll)
                    .horizontalScroll(rememberScrollState())
                    .padding(10.dp),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth().heightIn(max = 130.dp),
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("输入 Debian 命令") },
                minLines = 1,
                maxLines = 4,
                enabled = snapshot.state == PtySessionState.RUNNING,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = snapshot.state == PtySessionState.RUNNING && input.isNotBlank(),
                onClick = {
                    val command = input
                    input = ""
                    runAction { manager.sendLine(command) }
                },
            ) { Text("发送") }
        }
    }
}
