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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.luckylca.autocrack.root.ProcessRootCommandRunner
import com.luckylca.autocrack.root.RootDetector
import com.luckylca.autocrack.runtime.DynamicHostReadBridge
import com.luckylca.autocrack.runtime.HostDebuggerSessionManager
import com.luckylca.autocrack.runtime.HostLogcatSessionManager
import com.luckylca.autocrack.runtime.RuntimeLayout

private enum class AppScreen {
    MAIN,
    TOOLS,
    RUNTIME,
    LINUX,
    TOOLPACKS,
    TERMINAL,
    DYNAMIC,
    DEBUGGER,
}

@Composable
fun AutoCrackApp() {
    val darkTheme = androidx.compose.foundation.isSystemInDarkTheme()
    val appContext = LocalContext.current.applicationContext
    var screen by remember { mutableStateOf(AppScreen.MAIN) }
    val navigationScroll = rememberScrollState()

    val dynamicLayout = remember(appContext) { RuntimeLayout(appContext).initialize() }
    val dynamicRunner = remember { ProcessRootCommandRunner() }
    val dynamicRootDetector = remember(dynamicRunner) { RootDetector(dynamicRunner) }
    val dynamicReadBridge = remember(dynamicLayout, dynamicRootDetector, dynamicRunner) {
        DynamicHostReadBridge(dynamicLayout, dynamicRootDetector, dynamicRunner)
    }
    val logcatSessionManager = remember(dynamicLayout, dynamicRootDetector, dynamicRunner) {
        HostLogcatSessionManager(dynamicLayout, dynamicRootDetector, dynamicRunner)
    }
    val debuggerSessionManager = remember(
        appContext,
        dynamicLayout,
        dynamicRootDetector,
        dynamicRunner,
    ) {
        HostDebuggerSessionManager(
            context = appContext,
            layout = dynamicLayout,
            rootDetector = dynamicRootDetector,
            runner = dynamicRunner,
        )
    }

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
                    AppScreen.DYNAMIC -> DynamicInspectionScreen(
                        bridge = dynamicReadBridge,
                        logcatSessionManager = logcatSessionManager,
                    )
                    AppScreen.DEBUGGER -> DebuggerSessionScreen(
                        bridge = dynamicReadBridge,
                        manager = debuggerSessionManager,
                    )
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
                    FilledTonalButton(onClick = { screen = AppScreen.DYNAMIC }) {
                        Text("动态")
                    }
                    FilledTonalButton(onClick = { screen = AppScreen.DEBUGGER }) {
                        Text("调试")
                    }
                }
            }
        }
    }
}
