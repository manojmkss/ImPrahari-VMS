package com.imprahari.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Same palette as the web app (frontend/src/styles.css).
private val NvrColors = darkColorScheme(
    primary = Color(0xFF3B82F6),
    background = Color(0xFF0F1216),
    surface = Color(0xFF171C22),
    surfaceVariant = Color(0xFF1B2128),
    onSurfaceVariant = Color(0xFF9AA4B2),
    error = Color(0xFFEF4444),
)

val StatusOnline = Color(0xFF22C55E)
val StatusOffline = Color(0xFF6B7280)

@Composable
fun ImPrahariTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = NvrColors, content = content)
}
