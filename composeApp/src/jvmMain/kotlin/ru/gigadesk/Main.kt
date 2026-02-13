@file:OptIn(FlowPreview::class)

package ru.gigadesk

import gigadesk.composeapp.generated.resources.Res
import gigadesk.composeapp.generated.resources.iconT
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.painterResource
import org.kodein.di.compose.localDI
import org.kodein.di.compose.withDI
import org.kodein.di.instance
import ru.gigadesk.audio.Say
import ru.gigadesk.db.SettingsProvider
import ru.gigadesk.di.mainDiModule
import ru.gigadesk.mcp.McpClientManager
import ru.gigadesk.server.AgentNode
import ru.gigadesk.server.startLocalServer

import androidx.compose.ui.res.painterResource as jvmPainterResource

val LocalWindowScope = staticCompositionLocalOf<WindowScope?> { null }

fun main() {
    //System.setProperty("apple.awt.UIElement", "true") // - Makes the app tray-only on macOS

    application {
        withDI(mainDiModule) {
            val di = localDI()
            val say: Say by di.instance()
            val settingsProvider: SettingsProvider by di.instance()
            val agentNode: AgentNode by di.instance()
            val mcpClientManager: McpClientManager by di.instance()

            DisposableEffect(Unit) {
                println("Starting local server...")
                val serverEngine = startLocalServer(agentNode)

                onDispose {
                    println("Stopping local server...")
                    serverEngine.stop(1000, 2000)
                    mcpClientManager.close()
                }
            }
            
            try {
                if (System.getProperty("os.name").contains("Mac")) {
                    Thread.currentThread().contextClassLoader
                        .getResourceAsStream("icon-light.png")?.use {
                            java.awt.Taskbar.getTaskbar().iconImage = javax.imageio.ImageIO.read(it)
                        }
                }
            } catch (e: Exception) {
                println("Failed to set dock icon: ${e.message}")
            }

            var isWindowVisible by remember { mutableStateOf(true) }

            Tray(
                icon = painterResource(Res.drawable.iconT),
                tooltip = "gigadesk AI",
                onAction = { isWindowVisible = !isWindowVisible },
                menu = {
                    Item("Показать/Скрыть", onClick = { isWindowVisible = !isWindowVisible })
                    Separator()

                    Item("Выключить звук", onClick = {
                        say.clearQueue()
                    })
                    Separator()

                    Item("Выход", onClick = ::exitApplication)
                }
            )

            val initialWidth = settingsProvider.initialWindowWidthDp.dp
            val initialHeight = settingsProvider.initialWindowHeightDp.dp

            val windowState = rememberWindowState(
                width = initialWidth,
                height = initialHeight,
                position = WindowPosition.Aligned(Alignment.BottomEnd)
            )

            Window(
                onCloseRequest = { isWindowVisible = false },
                visible = isWindowVisible,
                title = "Союз c ИИ",
                icon = jvmPainterResource("icon-light.png"),
                state = windowState,
                transparent = true,
                undecorated = true,
                resizable = true,
                alwaysOnTop = false
            ) {
                LaunchedEffect(windowState) {
                    snapshotFlow { windowState.size }
                        .distinctUntilChanged()
                        .debounce(1000)
                        .collect { size ->
                            settingsProvider.initialWindowWidthDp = size.width.value.roundToInt()
                            settingsProvider.initialWindowHeightDp = size.height.value.roundToInt()
                        }
                }
                // Provide WindowScope to nested composables via CompositionLocal
                CompositionLocalProvider(LocalWindowScope provides this) {
                    App(
                        onCloseWindow = { isWindowVisible = false }
                    )
                }
            }
        }
    }
}