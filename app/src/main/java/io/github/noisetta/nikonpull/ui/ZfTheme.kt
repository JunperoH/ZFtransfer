package io.github.noisetta.nikonpull.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ZfColorScheme = darkColorScheme(
    primary = Color(0xFFF5D547),
    onPrimary = Color(0xFF201C08),
    primaryContainer = Color(0xFF423A17),
    onPrimaryContainer = Color(0xFFFFE999),
    secondary = Color(0xFFD2CCAF),
    onSecondary = Color(0xFF312E20),
    secondaryContainer = Color(0xFF484435),
    onSecondaryContainer = Color(0xFFF0E8C9),
    background = Color(0xFF111310),
    onBackground = Color(0xFFF2F1E9),
    surface = Color(0xFF181A17),
    onSurface = Color(0xFFF2F1E9),
    surfaceVariant = Color(0xFF292C27),
    onSurfaceVariant = Color(0xFFC5C8BE),
    outline = Color(0xFF8E9185),
    outlineVariant = Color(0xFF44483F),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

@Composable
fun ZfTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ZfColorScheme, content = content)
}
