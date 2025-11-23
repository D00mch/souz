package ru.abledo

import androidx.compose.runtime.Composable
import ru.abledo.ui.AppTheme
import org.jetbrains.compose.ui.tooling.preview.Preview
import ru.abledo.ui.settings.SettingsScreen

@Composable
@Preview
fun App() {
    AppTheme {
        SettingsScreen()
    }
}