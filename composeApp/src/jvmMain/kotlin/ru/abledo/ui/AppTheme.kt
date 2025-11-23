package ru.abledo.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.github.kdroidfilter.platformtools.darkmodedetector.isSystemInDarkMode

@Composable
fun AppTheme(
    forceDark: Boolean = false,
    content: @Composable () -> Unit
) {
    val isDark = isSystemInDarkMode()
    val colorScheme = if (forceDark || isDark) DarkColors else LightColors

    MaterialTheme(colorScheme = colorScheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            content()
        }
    }
}

private val DarkColors = darkColorScheme(
    primary = Color(0xFF12E0B5),
    onPrimary = Color(0xFF001A14),
    secondary = Color(0xFFA58BFE),
    onSecondary = Color(0xFF161324),
    tertiary = Color(0xFFFFB86C),
    onTertiary = Color(0xFF241300),
    background = Color(0xFF0B0E11),
    onBackground = Color(0xFFE4E7EB),
    surface = Color(0xFF0E1114),
    onSurface = Color(0xFFE4E7EB),
    surfaceVariant = Color(0xFF171B20),
    onSurfaceVariant = Color(0xFFBDC3CA),
    outline = Color(0xFF31363C),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF6200EE),
    onPrimary = Color.White,
    secondary = Color(0xFF6C63FF),
    onSecondary = Color.White,
    tertiary = Color(0xFFFF6B6B),
    onTertiary = Color.White,
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1A1A1A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFF1F1F1),
    onSurfaceVariant = Color(0xFF444746),
    outline = Color(0x1F000000),
)
