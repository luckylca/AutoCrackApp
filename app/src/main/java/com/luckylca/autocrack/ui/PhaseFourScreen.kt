package com.luckylca.autocrack.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.luckylca.autocrack.analysis.LatestAnalysisStore

@Composable
fun PhaseFourScreen() {
    val latestReport by LatestAnalysisStore.latestReport.collectAsState()
    var showTestCard by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(latestReport?.completedAtEpochMillis) {
        if (latestReport != null) {
            showTestCard = true
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        PhaseThreeScreen()

        if (latestReport != null) {
            Button(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(16.dp),
                onClick = { showTestCard = true },
            ) {
                Text("打开测试结果卡")
            }
        }
    }

    val report = latestReport
    if (showTestCard && report != null) {
        TestResultDialog(
            report = report,
            onDismiss = { showTestCard = false },
        )
    }
}
