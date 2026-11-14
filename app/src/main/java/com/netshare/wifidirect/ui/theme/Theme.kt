package com.netshare.wifidirect.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Sky400,
    onPrimary = Slate950,
    primaryContainer = Slate800,
    onPrimaryContainer = Color.White,
    secondary = Emerald400,
    onSecondary = Slate950,
    background = Slate950,
    onBackground = Color(0xFFF1F5F9),
    surface = Slate900,
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Slate800,
    onSurfaceVariant = Color(0xFF94A3B8),
    error = Rose500
)

@Composable
fun NetShareTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}

