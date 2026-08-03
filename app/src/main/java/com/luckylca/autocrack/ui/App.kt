package com.luckylca.autocrack.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
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
    LINUX,
    TOOLPACKS,
    TERMINAL,
}

@Composable
fun AutoCrackApp() {
    val darkTheme = androidx.compose.foundation.isSystemInDarkTheme()
    var screen by remember { mutableStateOf(AppScreen.MAIN) }
    val navigationScroll = rememberScrollState()

    MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
                when (screen) {
                    AppScreen.MAIN -> PhaseFiveTabbedScreen()
                    AppScreen.TOOLS -> AnalysisToolsScreen()
                    AppScreen.RUNTIME -> RuntimeFoundationScreen()
                    AppScreen.LINUX -> ChrootRuntimeScreen()
                    AppScreen.TOOLPACKS -> ToolpackScreen()
                    AppScreen.TERMINAL -> PtyTerminalScreen()
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .horizontalScroll(navigationScroll)
                        .padding(top = 8.dp, start = 8.dp, end = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FilledTonalButton(onClick = { screen = AppScreen.MAIN }) {
                        Text("首页")
                    }
                    FilledTonalButton(onClick = { screen = AppScreen.TOOLS }) {
                        Text("工具")
                    }
                    FilledTonalButton(onClick = { screen = AppScreen.RUNTIME }) {
                        Text("Root")
                    }
                    FilledTonalButton(onClick = { screen = AppScreen.LINUX }) {
                        Text("Linux")
                    }
                    FilledTonalButton(onClick = { screen = AppScreen.TOOLPACKS }) {
                        Text("工具包")
                    }
                    FilledTonalButton(onClick = { screen = AppScreen.TERMINAL }) {
                        Text("终端")
                    }
                }
            }
        }
    }
}
