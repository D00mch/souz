package ru.souz

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.window.WindowScope

val LocalWindowScope = staticCompositionLocalOf<WindowScope?> { null }
