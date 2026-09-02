package com.luckylca.autocrack.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

private val AutoCrackLightColors = lightColorScheme(
    primary = Color(0xFF006B5F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA5F2E5),
    onPrimaryContainer = Color(0xFF00201B),
    secondary = Color(0xFF4F5D95),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDEE2FF),
    onSecondaryContainer = Color(0xFF09164B),
    tertiary = Color(0xFF855400),
    tertiaryContainer = Color(0xFFFFDDB0),
    background = Color(0xFFFAFCFA),
    onBackground = Color(0xFF191C1B),
    surface = Color(0xFFFAFCFA),
    onSurface = Color(0xFF191C1B),
    surfaceVariant = Color(0xFFDAE5E1),
    onSurfaceVariant = Color(0xFF3F4946),
    outline = Color(0xFF6F7976),
    outlineVariant = Color(0xFFBEC9C5),
)

private val AutoCrackDarkColors = darkColorScheme(
    primary = Color(0xFF89D5C9),
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF005047),
    onPrimaryContainer = Color(0xFFA5F2E5),
    secondary = Color(0xFFBCC5FF),
    onSecondary = Color(0xFF202E63),
    secondaryContainer = Color(0xFF374577),
    onSecondaryContainer = Color(0xFFDEE2FF),
    tertiary = Color(0xFFFFB955),
    tertiaryContainer = Color(0xFF653E00),
    background = Color(0xFF101412),
    onBackground = Color(0xFFE1E3E0),
    surface = Color(0xFF101412),
    onSurface = Color(0xFFE1E3E0),
    surfaceVariant = Color(0xFF3F4946),
    onSurfaceVariant = Color(0xFFBEC9C5),
    outline = Color(0xFF899390),
    outlineVariant = Color(0xFF3F4946),
)

@Composable
internal fun AutoCrackApp(
    routeRequest: MobileAgentRouteRequest? = null,
    onRouteConsumed: () -> Unit = {},
    pictureInPictureState: MobileAgentPictureInPictureState? = null,
) {
    val darkTheme = isSystemInDarkTheme()
    val colors = if (darkTheme) AutoCrackDarkColors else AutoCrackLightColors
    MaterialTheme(colorScheme = colors) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
                MobilePiAgentScreen(routeRequest = routeRequest, onRouteConsumed = onRouteConsumed)
                pictureInPictureState?.let { state ->
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                        MobileAgentPictureInPictureContent(state)
                    }
                }
            }
        }
    }
}
