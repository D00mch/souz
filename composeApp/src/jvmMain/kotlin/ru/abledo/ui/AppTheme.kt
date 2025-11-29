package ru.abledo.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.dp

val SfDisplay = FontFamily(
    Font("SF Pro Display", FontWeight.Medium),
    Font("SF Pro Display", FontWeight.Normal),
    Font("San Francisco", FontWeight.Medium),
    Font("Helvetica Neue", FontWeight.Medium)
)

@Immutable
data class GlassColors(
    val backgroundTop: Color,
    val backgroundBottom: Color,
    val borderGlowTop: Color,
    val borderGlowBottom: Color,
    val textPrimary: Color,
    val orbCyan: Color,
    val orbIndigo: Color,
    val orbWhite: Color
)

val DefaultGlassColors = GlassColors(
    backgroundTop = Color(0x509CAAB8),
    backgroundBottom = Color(0x667D8C9B),
    borderGlowTop = Color(0x40FFFFFF),
    borderGlowBottom = Color(0x05FFFFFF),
    textPrimary = Color(0xD9FFFFFF),
    orbCyan = Color(0xFF00FFFF),
    orbIndigo = Color(0xFF6366F1),
    orbWhite = Color(0xFFFFFFFF)
)

val LocalGlassColors = staticCompositionLocalOf { DefaultGlassColors }

object GlassTheme {
    val colors: GlassColors
        @Composable
        get() = LocalGlassColors.current

    val typography = SfDisplay
    val shape = RoundedCornerShape(22.dp)
}

@Composable
fun AppTheme(
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // Стандартные цвета Material (для Settings и других экранов)
    val materialColors = if (useDarkTheme) DarkColors else LightColors

    // Внедряем нашу Glass-систему поверх Material
    CompositionLocalProvider(
        LocalGlassColors provides DefaultGlassColors
    ) {
        MaterialTheme(colorScheme = materialColors) {
            content()
        }
    }
}

// --- STANDARD MATERIAL COLORS (Твои текущие цвета) ---
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