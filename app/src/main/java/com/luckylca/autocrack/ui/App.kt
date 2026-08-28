package com.luckylca.autocrack.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun AutoCrackApp() {
    val darkTheme = androidx.compose.foundation.isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            MobilePiAgentScreen()
        }
    }
}
