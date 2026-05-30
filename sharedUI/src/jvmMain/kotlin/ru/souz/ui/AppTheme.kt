package ru.souz.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

@Composable
fun AppTheme(
    content: @Composable () -> Unit
) {
    // Внедряем нашу Glass-систему поверх Material
    CompositionLocalProvider(
        LocalGlassColors provides DefaultGlassColors,
        LocalGlassShape provides RoundedCornerShape(22.dp)
    ) {
        MaterialTheme(
            colorScheme = DarkColors,
            typography = AppTypography()
        ) {
            content()
        }
    }
}

@Composable
fun AppTypography(): Typography {

    val sfDisplay = FontFamily(
        Font("SF Pro Display", FontWeight.Medium),
        Font("SF Pro Display", FontWeight.Normal),
        Font("San Francisco", FontWeight.Medium),
        Font("Helvetica Neue", FontWeight.Medium)
    )

    return Typography(
        headlineLarge = TextStyle(
            fontFamily = sfDisplay,
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp
        ),
        bodyMedium = TextStyle(
            fontFamily = sfDisplay,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp
        ),
        displaySmall = TextStyle(
            fontFamily = sfDisplay,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp
        )
    )
}

private val DefaultGlassColors = GlassColors(
    backgroundTop = Color(0x509CAAB8),
    backgroundBottom = Color(0x667D8C9B),
    borderGlowTop = Color(0x40FFFFFF),
    borderGlowBottom = Color(0x05FFFFFF),
    textPrimary = Color(0xD9FFFFFF),
    orbCyan = Color(0xFF00FFFF),
    orbIndigo = Color(0xFF6366F1),
    orbWhite = Color(0xFFFFFFFF)
)

private val LocalGlassColors = staticCompositionLocalOf { DefaultGlassColors }
private val LocalGlassShape = staticCompositionLocalOf { RoundedCornerShape(22.dp) }

val MaterialTheme.glassColors: GlassColors
    @Composable
    get() = LocalGlassColors.current

val MaterialTheme.glassShape
    @Composable
    get() = LocalGlassShape.current

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
