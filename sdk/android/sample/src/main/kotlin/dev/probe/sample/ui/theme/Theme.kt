package dev.probe.sample.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFFFE81F),      // Star Wars yellow
    onPrimary = Color.Black,
    secondary = Color(0xFF4FC3F7),    // Light blue
    background = Color(0xFF0A0A0F),   // Space black
    surface = Color(0xFF1A1A2E),
    onBackground = Color.White,
    onSurface = Color.White
)

@Composable
fun ProbeSampleTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
