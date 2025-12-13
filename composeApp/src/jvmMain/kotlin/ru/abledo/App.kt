package ru.abledo

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize // <--- Import
import org.kodein.di.compose.localDI
import org.kodein.di.instance
import ru.abledo.ui.AppTheme
import ru.abledo.ui.main.MainScreen
import ru.abledo.ui.settings.SettingsScreen
import ru.abledo.ui.tools.ToolDetailsScreen
import ru.abledo.ui.tools.ToolsScreen
import ru.abledo.db.SettingsProvider
import ru.abledo.tool.ToolCategory
import java.util.UUID

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
        mutableStateOf<Screen>(if (shouldStartInSettings) Screen.Settings else Screen.Main)
    }

    AppTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Transparent
        ) {
            when (val screen = currentScreen) {
                Screen.Main -> MainScreen(
                    onOpenSettings = { currentScreen = Screen.Settings },
                    onResizeRequest = onWindowResize,
                    onCloseWindow = onCloseWindow
                )
                Screen.Settings -> SettingsScreen(
                    onClose = { currentScreen = Screen.Main },
                    onOpenTools = { currentScreen = Screen.Tools() },
                    onResizeRequest = onWindowResize
                )
                is Screen.Tools -> ToolsScreen(
                    onClose = { currentScreen = Screen.Settings },
                    onOpenToolDetails = { category, tool ->
                        currentScreen = Screen.ToolDetails(category, tool.name)
                    },
                    onResizeRequest = onWindowResize,
                    viewModelKey = screen.id,
                )
                is Screen.ToolDetails -> ToolDetailsScreen(
                    category = screen.category,
                    toolName = screen.toolName,
                    onClose = { currentScreen = Screen.Tools() },
                    onResizeRequest = onWindowResize,
                )
            }
        }
    }
}

private sealed interface Screen {
    data object Main : Screen
    data object Settings : Screen
    data class Tools(val id: String = UUID.randomUUID().toString()) : Screen
    data class ToolDetails(val category: ToolCategory, val toolName: String) : Screen
}