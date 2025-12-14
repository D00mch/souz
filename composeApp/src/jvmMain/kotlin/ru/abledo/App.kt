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
import ru.abledo.Screen.*
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
        mutableStateOf(if (shouldStartInSettings) Settings else Main)
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()

    AppTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Transparent
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                when (val screen = currentScreen) {
                    Main -> MainScreen(
                        onOpenSettings = { currentScreen = Settings },
                        onResizeRequest = onWindowResize,
                        onCloseWindow = onCloseWindow
                    )
                    Settings -> SettingsScreen(
                        onClose = { currentScreen = Main },
                        onOpenTools = { currentScreen = Tools() },
                        onResizeRequest = onWindowResize,
                    )
                    is Tools -> ToolsScreen(
                        onClose = { currentScreen = Settings },
                        onResizeRequest = onWindowResize,
                        onOpenToolDetails = { category, tool ->
                            currentScreen = ToolDetails(category, tool.name)
                        },
                        onShowSnack = { message ->
                            snackbarScope.launch { snackbarHostState.showSnackbar(message) }
                        },
                        viewModelKey = screen.id,
                    )
                    is ToolDetails -> ToolDetailsScreen(
                        category = screen.category,
                        toolName = screen.toolName,
                        onClose = { currentScreen = Tools() },
                        onResizeRequest = onWindowResize,
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

private sealed interface Screen {
    data object Main : Screen
    data object Settings : Screen
    data class Tools(val id: String = UUID.randomUUID().toString()) : Screen
    data class ToolDetails(val category: ToolCategory, val toolName: String) : Screen
}