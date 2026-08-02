package com.luckylca.autocrack.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.luckylca.autocrack.runtime.ChrootPtySessionManager
import com.luckylca.autocrack.runtime.PtyProcessInfo
import com.luckylca.autocrack.runtime.PtySessionSnapshot
import com.luckylca.autocrack.runtime.PtySessionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun PtyProcessSupervisorPanel(
    manager: ChrootPtySessionManager,
    snapshot: PtySessionSnapshot,
) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("等待 PTY 会话") }
    var forceKillArmed by remember { mutableStateOf(false) }

    LaunchedEffect(forceKillArmed) {
        if (forceKillArmed) {
            delay(FORCE_KILL_CONFIRM_WINDOW_MILLIS)
            forceKillArmed = false
        }
    }

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
                "进程监督",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            HorizontalDivider()
            Text(
                "只显示当前 AutoCrackApp PTY 根进程及其后代；不会扫描或操作无关应用进程。",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "根 PID：${snapshot.processTree.rootPid ?: snapshot.pid ?: "无"} · " +
                    "进程数：${snapshot.processTree.processes.size}",
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "刷新时间：${snapshot.processTree.refreshedAtEpochMillis ?: "未刷新"}",
                fontFamily = FontFamily.Monospace,
            )
            snapshot.processTree.failure?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = snapshot.state == PtySessionState.RUNNING,
                    onClick = {
                        scope.launch {
                            runCatching { manager.refreshProcessTree() }
                                .onSuccess { tree -> status = "已刷新 ${tree.processes.size} 个进程" }
                                .onFailure { error ->
                                    status = error.message ?: error::class.java.name
                                }
                        }
                    },
                ) {
                    Text("刷新进程树")
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = snapshot.state == PtySessionState.RUNNING,
                    onClick = {
                        scope.launch {
                            runCatching {
                                check(manager.terminateProcessGroup()) { "SIGTERM 发送失败" }
                            }.onSuccess {
                                status = "已向 PTY 进程组发送 SIGTERM"
                            }.onFailure { error ->
                                status = error.message ?: error::class.java.name
                            }
                        }
                    },
                ) {
                    Text("优雅终止")
                }
            }

            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = snapshot.state == PtySessionState.RUNNING,
                onClick = {
                    if (!forceKillArmed) {
                        forceKillArmed = true
                        status = "5 秒内再次点击以发送 SIGKILL"
                    } else {
                        forceKillArmed = false
                        scope.launch {
                            runCatching {
                                check(manager.killProcessGroup()) { "SIGKILL 发送失败" }
                            }.onSuccess {
                                status = "已向 PTY 进程组发送 SIGKILL"
                            }.onFailure { error ->
                                status = error.message ?: error::class.java.name
                            }
                        }
                    }
                },
            ) {
                Text(if (forceKillArmed) "再次点击：强制结束" else "强制结束进程组")
            }

            Text(status, style = MaterialTheme.typography.bodySmall)

            if (snapshot.processTree.processes.isEmpty()) {
                Text("尚无进程快照")
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    snapshot.processTree.processes.take(MAX_VISIBLE_PROCESSES).forEach { process ->
                        ProcessRow(process)
                    }
                    if (snapshot.processTree.processes.size > MAX_VISIBLE_PROCESSES) {
                        Text(
                            "其余 ${snapshot.processTree.processes.size - MAX_VISIBLE_PROCESSES} 个进程已省略，完整列表位于复制诊断中。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProcessRow(process: PtyProcessInfo) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "PID=${process.pid} PPID=${process.parentPid} PGID=${process.processGroupId} " +
                "SID=${process.sessionId} ${process.state}",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            process.commandLine.ifBlank { process.name },
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private const val MAX_VISIBLE_PROCESSES = 20
private const val FORCE_KILL_CONFIRM_WINDOW_MILLIS = 5_000L
