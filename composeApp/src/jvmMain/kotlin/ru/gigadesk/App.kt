package ru.gigadesk

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.SnackbarHost
import androidx.compose.material.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.gigadesk.Screen.*
import ru.gigadesk.tool.ToolCategory
import ru.gigadesk.ui.AppTheme
import ru.gigadesk.ui.main.MainScreen
import ru.gigadesk.ui.settings.SettingsScreen
import ru.gigadesk.ui.tools.ToolDetailsScreen
import ru.gigadesk.ui.tools.ToolsScreen
import java.util.*

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
@Preview
fun App(
    onWindowResize: (DpSize) -> Unit,
    onCloseWindow: () -> Unit
) {
    var currentScreen: Screen by remember { mutableStateOf(Main) }
    var toolsScreen by remember { mutableStateOf<Tools?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()

    AppTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Transparent
        ) {
            SharedTransitionLayout {
                Box(modifier = Modifier.fillMaxSize()) {
                    AnimatedContent(
                        targetState = currentScreen,
                        transitionSpec = {
                            ContentTransform(
                                targetContentEnter = fadeIn(animationSpec = tween(5)),
                                initialContentExit = fadeOut(animationSpec = tween(5)),
                                targetContentZIndex = 1f,
                                sizeTransform = SizeTransform(clip = false),
                            )
                        },
                    ) { screen ->
                        when (screen) {
                            Main -> MainScreen(
                                onOpenSettings = { currentScreen = Settings },
                                onResizeRequest = onWindowResize,
                                onCloseWindow = onCloseWindow,
                                onShowSnack = { message ->
                                    snackbarScope.launch { snackbarHostState.showSnackbar(message) }
                                },
                            )
                            Settings -> SettingsScreen(
                                onClose = { currentScreen = Main },
                                onOpenTools = {
                                    if (toolsScreen == null) {
                                        toolsScreen = Tools()
                                    }
                                    currentScreen = toolsScreen ?: Tools()
                                },
                                onResizeRequest = onWindowResize,
                                onShowSnack = { message ->
                                    snackbarScope.launch { snackbarHostState.showSnackbar(message) }
                                },
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
                                sharedTransitionScope = this@SharedTransitionLayout,
                                animatedVisibilityScope = this,
                            )
                            is ToolDetails -> ToolDetailsScreen(
                                category = screen.category,
                                toolName = screen.toolName,
                                onClose = {
                                    if (toolsScreen == null) {
                                        toolsScreen = Tools()
                                    }
                                    currentScreen = toolsScreen ?: Tools()
                                },
                                onResizeRequest = onWindowResize,
                                sharedTransitionScope = this@SharedTransitionLayout,
                                animatedVisibilityScope = this,
                            )
                        }
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
}

private sealed interface Screen {
    data object Main : Screen
    data object Settings : Screen
    data class Tools(val id: String = UUID.randomUUID().toString()) : Screen
    data class ToolDetails(val category: ToolCategory, val toolName: String) : Screen
}
