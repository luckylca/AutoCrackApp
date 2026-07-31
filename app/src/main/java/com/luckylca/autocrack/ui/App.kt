package com.luckylca.autocrack.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.luckylca.autocrack.root.ProcessRootCommandRunner
import com.luckylca.autocrack.root.RootAccessState
import com.luckylca.autocrack.root.RootDetector
import com.luckylca.autocrack.root.RootProvider
import com.luckylca.autocrack.root.RootStatus

private sealed interface RootUiState {
    data object Loading : RootUiState
    data class Ready(val status: RootStatus) : RootUiState
}

@Composable
fun AutoCrackApp() {
    val darkTheme = androidx.compose.foundation.isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            PhaseOneScreen()
        }
    }
}

@Composable
private fun PhaseOneScreen() {
    val inspectionMode = LocalInspectionMode.current
    val detector = remember { RootDetector(ProcessRootCommandRunner()) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var rootState by remember { mutableStateOf<RootUiState>(RootUiState.Loading) }

    LaunchedEffect(refreshKey, inspectionMode) {
        rootState = if (inspectionMode) {
            RootUiState.Ready(previewRootStatus())
        } else {
            RootUiState.Loading
            RootUiState.Ready(detector.inspect())
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "AutoCrackApp",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Phase 1 · Android Root Runtime",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "第一阶段只执行只读环境探测，不提取 APK、不执行目标应用代码，也不会把 Shell 命令交给模型。",
            style = MaterialTheme.typography.bodyLarge,
        )

        DeviceCard()

        when (val state = rootState) {
            RootUiState.Loading -> LoadingCard()
            is RootUiState.Ready -> RootStatusCard(state.status)
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = { refreshKey += 1 },
            enabled = rootState !is RootUiState.Loading,
        ) {
            Text("重新检测 Root / KernelSU")
        }

        Text(
            text = "下一阶段：列出已安装应用，并通过类型化 Root 工具提取 base.apk 与 Split APK。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DeviceCard() {
    InfoCard(title = "设备环境") {
        InfoRow("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        InfoRow("设备", "${Build.MANUFACTURER} ${Build.MODEL}")
        InfoRow("ABI", Build.SUPPORTED_ABIS.joinToString())
    }
}

@Composable
private fun LoadingCard() {
    InfoCard(title = "Root 状态") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator()
            Column {
                Text("正在检测 su 与授权状态", fontWeight = FontWeight.SemiBold)
                Text("KernelSU 可能弹出授权窗口。")
            }
        }
    }
}

@Composable
private fun RootStatusCard(status: RootStatus) {
    InfoCard(title = "Root 状态") {
        InfoRow("访问状态", status.accessState.displayName())
        InfoRow("Root 管理器", status.provider.displayName())
        InfoRow("su 路径", status.suPath ?: "未找到")
        InfoRow("版本名称", status.versionName ?: "未知")
        InfoRow("版本代码", status.versionCode ?: "未知")
        InfoRow("UID / GID", status.identity?.let { "${it.uid ?: "?"} / ${it.gid ?: "?"}" } ?: "未知")
        InfoRow("SELinux", status.identity?.selinuxContext ?: "未知")

        status.diagnostic?.let {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun InfoCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
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
private fun InfoRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun RootAccessState.displayName(): String = when (this) {
    RootAccessState.NOT_AVAILABLE -> "未发现 Root"
    RootAccessState.PERMISSION_REQUIRED -> "等待授权或授权超时"
    RootAccessState.GRANTED -> "已授权"
    RootAccessState.DENIED -> "已拒绝"
    RootAccessState.ERROR -> "检测错误"
}

private fun RootProvider.displayName(): String = when (this) {
    RootProvider.KERNEL_SU -> "KernelSU"
    RootProvider.OTHER -> "其他 su 实现"
    RootProvider.UNKNOWN -> "未知"
}

private fun previewRootStatus(): RootStatus = RootStatus(
    accessState = RootAccessState.GRANTED,
    provider = RootProvider.KERNEL_SU,
    suPath = "/system/bin/su",
    versionName = "KernelSU preview",
    versionCode = "preview",
    identity = com.luckylca.autocrack.root.UnixIdentity(
        uid = 0,
        gid = 0,
        selinuxContext = "u:r:ksu:s0",
    ),
    evidence = listOf("KernelSU preview"),
    diagnostic = null,
)
