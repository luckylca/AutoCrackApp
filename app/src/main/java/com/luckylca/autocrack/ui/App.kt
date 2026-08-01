package com.luckylca.autocrack.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun AutoCrackApp() {
    val darkTheme = androidx.compose.foundation.isSystemInDarkTheme()
    var showTools by remember { mutableStateOf(false) }

    MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (showTools) {
                    AnalysisToolsScreen()
                } else {
                    PhaseFiveTabbedScreen()
                }

                FilledTonalButton(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(top = 8.dp, end = 12.dp),
                    onClick = { showTools = !showTools },
                ) {
                    Text(if (showTools) "返回" else "工具箱")
                }
            }
        }
    }
}
