package ru.souz.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.DrawableResource
import souz.sharedui.generated.resources.Res
import souz.sharedui.generated.resources.welcome_logo_dark
import souz.sharedui.generated.resources.welcome_logo_light

@Immutable
data class SouzAssets(
    val welcomeLogo: DrawableResource,
)

private val DarkSouzAssets = SouzAssets(Res.drawable.welcome_logo_dark)
private val LightSouzAssets = SouzAssets(Res.drawable.welcome_logo_light)

internal fun souzAssets(isDark: Boolean): SouzAssets =
    if (isDark) DarkSouzAssets else LightSouzAssets

@Composable
fun AppTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    systemDark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val isDark = themeMode.resolve(systemDark)
    CompositionLocalProvider(
        LocalSouzColors provides souzColors(isDark),
        LocalSouzAssets provides souzAssets(isDark),
        LocalGlassShape provides RoundedCornerShape(22.dp)
    ) {
        MaterialTheme(
            colorScheme = if (isDark) DarkColors else LightColors,
            typography = AppTypography()
        ) {
            content()
        }
    }
}

@Composable
fun AppTypography(): Typography {
    val appFontFamily = FontFamily.SansSerif

    return Typography(
        headlineLarge = TextStyle(
            fontFamily = appFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp
        ),
        bodyMedium = TextStyle(
            fontFamily = appFontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp
        ),
        displaySmall = TextStyle(
            fontFamily = appFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp
        )
    )
}

private val LocalGlassShape = staticCompositionLocalOf { RoundedCornerShape(22.dp) }
private val LocalSouzAssets = staticCompositionLocalOf<SouzAssets> {
    error("SouzAssets are not provided")
}

val MaterialTheme.glassColors: GlassColors
    @Composable
    get() = MaterialTheme.souzColors.glass

val MaterialTheme.glassShape
    @Composable
    get() = LocalGlassShape.current

val MaterialTheme.souzAssets: SouzAssets
    @Composable get() = LocalSouzAssets.current

internal val DarkColors = darkColorScheme(
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
    errorContainer = Color(0xFF5A1A16),
    onErrorContainer = Color(0xFFFFB4AB),
)

internal val LightColors = lightColorScheme(
    primary = Color(0xFF0D7C66),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDDEFEA),
    onPrimaryContainer = Color(0xFF123E35),
    secondary = Color(0xFF5E5ADB),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE7E6FF),
    onSecondaryContainer = Color(0xFF29265F),
    tertiary = Color(0xFF9A6700),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF7F7F5),
    onBackground = Color(0xFF20201E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF20201E),
    surfaceVariant = Color(0xFFEFEFEC),
    onSurfaceVariant = Color(0xFF666661),
    outline = Color(0xFFC9C9C3),
    outlineVariant = Color(0xFFE3E3DE),
    error = Color(0xFFB42318),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9E7E5),
    onErrorContainer = Color(0xFF9D2118),
)
