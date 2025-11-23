package ru.abledo

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.runtime.Composable
import ru.abledo.ui.AppTheme
import ru.abledo.ui.main.MainScreen

@Composable
@Preview
fun App() {
    AppTheme {
        MainScreen()
    }
}