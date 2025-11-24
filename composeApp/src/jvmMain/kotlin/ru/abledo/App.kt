package ru.abledo

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.kodein.di.compose.localDI
import org.kodein.di.instance
import ru.abledo.ui.AppTheme
import ru.abledo.ui.main.MainScreen
import ru.abledo.ui.settings.SettingsScreen
import ru.abledo.db.KeysProvider

@Composable
@Preview
fun App() {
    val di = localDI()
    val keysProvider: KeysProvider by di.instance()
    val shouldStartInSettings = remember(keysProvider) {
        keysProvider.gigaChatKey.isNullOrEmpty() || keysProvider.saluteSpeechKey.isNullOrEmpty()
    }
    var currentScreen by remember(shouldStartInSettings) {
        mutableStateOf(if (shouldStartInSettings) Screen.Settings else Screen.Main)
    }

    AppTheme {
        when (currentScreen) {
            Screen.Main -> MainScreen(onOpenSettings = { currentScreen = Screen.Settings })
            Screen.Settings -> SettingsScreen(onClose = { currentScreen = Screen.Main })
        }
    }
}

private enum class Screen {
    Main,
    Settings,
}