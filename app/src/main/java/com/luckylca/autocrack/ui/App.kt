package com.luckylca.autocrack.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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

private enum class AppScreen {
    MAIN,
    TOOLS,
    RUNTIME,
}

@Composable
fun AutoCrackApp() {
    val darkTheme = androidx.compose.foundation.isSystemInDarkTheme()
    var screen by remember { mutableStateOf(AppScreen.MAIN) }

    MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
                when (screen) {
                    AppScreen.MAIN -> PhaseFiveTabbedScreen()
                    AppScreen.TOOLS -> AnalysisToolsScreen()
                    AppScreen.RUNTIME -> RuntimeFoundationScreen()
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(top = 8.dp, end = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilledTonalButton(onClick = { screen = AppScreen.MAIN }) {
                        Text("主页")
                    }
                    FilledTonalButton(onClick = { screen = AppScreen.TOOLS }) {
                        Text("工具")
                    }
                    FilledTonalButton(onClick = { screen = AppScreen.RUNTIME }) {
                        Text("运行时")
                    }
                }
            }
        }
    }
}
