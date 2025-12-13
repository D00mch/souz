package ru.abledo

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.SnackbarHost
import androidx.compose.material.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize // <--- Import
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import org.kodein.di.compose.localDI
import org.kodein.di.instance
import ru.abledo.ui.AppTheme
import ru.abledo.ui.main.MainScreen
import ru.abledo.ui.settings.SettingsScreen
import ru.abledo.ui.tools.ToolsScreen
import ru.abledo.db.SettingsProvider

@Composable
@Preview
fun App(
    onWindowResize: (DpSize) -> Unit,
    onCloseWindow: () -> Unit
) {
    val di = localDI()
    val keysProvider: SettingsProvider by di.instance()
    val shouldStartInSettings = remember(keysProvider) {
        keysProvider.gigaChatKey.isNullOrEmpty() || keysProvider.saluteSpeechKey.isNullOrEmpty()
    }
    var currentScreen by remember(shouldStartInSettings) {
        mutableStateOf(if (shouldStartInSettings) Screen.Settings else Screen.Main)
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()

    AppTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Transparent
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                when (currentScreen) {
                    Screen.Main -> MainScreen(
                        onOpenSettings = { currentScreen = Screen.Settings },
                        onResizeRequest = onWindowResize,
                        onCloseWindow = onCloseWindow
                    )
                    Screen.Settings -> SettingsScreen(
                        onClose = { currentScreen = Screen.Main },
                        onOpenTools = { currentScreen = Screen.Tools },
                        onResizeRequest = onWindowResize
                    )
                    Screen.Tools -> ToolsScreen(
                        onClose = { currentScreen = Screen.Settings },
                        onResizeRequest = onWindowResize,
                        onShowSnackbar = { message ->
                            snackbarScope.launch { snackbarHostState.showSnackbar(message) }
                        },
                    )
                }

                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp)
                )
            }
        }
    }
}

private enum class Screen { Main, Settings, Tools }