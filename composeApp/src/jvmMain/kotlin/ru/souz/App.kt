package ru.souz

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
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.souz.Screen.*
import ru.souz.tool.ToolCategory
import ru.souz.ui.AppTheme
import ru.souz.ui.main.MainScreen
import ru.souz.ui.setup.SetupScreen
import ru.souz.ui.settings.SettingsScreen
import ru.souz.ui.tools.ToolDetailsScreen
import ru.souz.ui.tools.ToolsScreen
import java.util.*
import java.net.HttpURLConnection
import java.net.URI

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
@Preview
fun App(
    onCloseWindow: () -> Unit,
    onHideWindow: () -> Unit,
    onMinimizeWindow: () -> Unit
) {
    var currentScreen: Screen by remember { mutableStateOf(Setup) }
    var toolsScreen by remember { mutableStateOf<Tools?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    val isOnline = remember { mutableStateOf(true) }

    fun navigateBackFromScreen(screen: Screen): Boolean {
        return when (screen) {
            Setup,
            Main,
            -> false

            Settings -> {
                currentScreen = Main
                true
            }

            is Tools -> {
                currentScreen = Settings
                true
            }

            is ToolDetails -> {
                if (toolsScreen == null) {
                    toolsScreen = Tools()
                }
                currentScreen = toolsScreen ?: Tools()
                true
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            val online = withContext(Dispatchers.IO) { isInternetAvailable() }
            if (online != isOnline.value) {
                isOnline.value = online
            }
            delay(3_000)
        }
    }

    AppTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Transparent
        ) {
            SharedTransitionLayout {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                                navigateBackFromScreen(currentScreen)
                            } else {
                                false
                            }
                        }
                ) {
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
                            Setup -> SetupScreen(
                                onOpenMain = { currentScreen = Main },
                            )
                            Main -> MainScreen(
                                onOpenSettings = { currentScreen = Settings },
                                onCloseWindow = onCloseWindow,
                                onHideWindow = onHideWindow,
                                onMinimizeWindow = onMinimizeWindow,
                                onShowSnack = { message ->
                                    snackbarScope.launch { snackbarHostState.showSnackbar(message) }
                                },
                                isOnline = isOnline.value
                            )
                            Settings -> SettingsScreen(
                                onClose = { currentScreen = Main },
                                onOpenTools = {
                                    if (toolsScreen == null) {
                                        toolsScreen = Tools()
                                    }
                                    currentScreen = toolsScreen ?: Tools()
                                },
                                onShowSnack = { message ->
                                    snackbarScope.launch { snackbarHostState.showSnackbar(message) }
                                },
                            )
                            is Tools -> ToolsScreen(
                                onClose = { currentScreen = Settings },
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
    data object Setup : Screen
    data object Main : Screen
    data object Settings : Screen
    data class Tools(val id: String = UUID.randomUUID().toString()) : Screen
    data class ToolDetails(val category: ToolCategory, val toolName: String) : Screen
}

private fun isInternetAvailable(): Boolean {
    return try {
        val connection = URI("https://clients3.google.com/generate_204").toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 1500
        connection.readTimeout = 1500
        connection.instanceFollowRedirects = false
        connection.requestMethod = "GET"
        connection.connect()
        connection.responseCode == 204
    } catch (e: Exception) {
        false
    }
}
