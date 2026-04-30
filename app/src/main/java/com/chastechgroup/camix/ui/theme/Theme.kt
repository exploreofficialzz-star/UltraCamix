package com.chastechgroup.camix.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary         = PrimaryPurple,
    secondary       = AccentTeal,
    background      = BackgroundDark,
    surface         = SurfaceDark,
    onPrimary       = TextPrimary,
    onSecondary     = BackgroundDark,
    onBackground    = TextPrimary,
    onSurface       = TextPrimary,
    error           = ShutterRed,
    surfaceVariant  = SurfaceLight,
    onSurfaceVariant= TextSecondary
)

@Composable
fun CamixTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography  = CamixTypography,
        content     = content
    )
}
