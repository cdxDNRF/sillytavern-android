package com.sillytavern.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val TavernDarkColorScheme = darkColorScheme(
    primary = Color(0xFFF5B041),
    onPrimary = Color(0xFF1F1600),
    secondary = Color(0xFF8E9BFF),
    onSecondary = Color(0xFF0E1438),
    background = Color(0xFF121318),
    onBackground = Color(0xFFECEEF4),
    surface = Color(0xFF1B1D26),
    onSurface = Color(0xFFECEEF4),
    surfaceVariant = Color(0xFF252836),
    onSurfaceVariant = Color(0xFFB4B9CB),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF290000),
)

@Composable
fun SillyTavernAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TavernDarkColorScheme,
        content = content,
    )
}
