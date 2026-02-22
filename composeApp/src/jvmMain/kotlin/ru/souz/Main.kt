@file:OptIn(FlowPreview::class)

package ru.souz

import souz.composeapp.generated.resources.Res
import souz.composeapp.generated.resources.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt
import kotlin.system.exitProcess
import org.slf4j.LoggerFactory
import org.kodein.di.compose.localDI
import org.kodein.di.compose.withDI
import org.kodein.di.instance
import ru.souz.audio.Say
import ru.souz.db.SettingsProvider
import ru.souz.di.mainDiModule
import ru.souz.mcp.McpClientManager
import ru.souz.ui.AppTray
import ru.souz.ui.rememberTrayWindowController

import androidx.compose.ui.res.painterResource as jvmPainterResource

val LocalWindowScope = staticCompositionLocalOf<WindowScope?> { null }
private val startupLog = LoggerFactory.getLogger("AppStartup")

/**
 * for EN build, pass VM options
 * ```
 * -Dorg.gradle.project.edition=en
 * ```
 */
fun main() {
    //System.setProperty("apple.awt.UIElement", "true") // - Makes the app tray-only on macOS
    logStartupPlatformInfo()

    application(exitProcessOnExit = false) {
        withDI(mainDiModule) {
            val di = localDI()
            val say: Say by di.instance()
            val settingsProvider: SettingsProvider by di.instance()
            val mcpClientManager: McpClientManager by di.instance()
            val telegramBotController: ru.souz.service.telegram.TelegramBotController by di.instance()

            DisposableEffect(Unit) {
                telegramBotController.start()

                onDispose {
                    println("Shutting down services...")
                    runCatching { mcpClientManager.close() }
                        .onFailure { println("Failed to close MCP manager: ${it.message}") }
                    runCatching { telegramBotController.close() }
                        .onFailure { println("Failed to close Telegram bot controller: ${it.message}") }
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

            val initialWidth = settingsProvider.initialWindowWidthDp.dp
            val initialHeight = settingsProvider.initialWindowHeightDp.dp

            val windowState = rememberWindowState(
                width = initialWidth,
                height = initialHeight,
                position = WindowPosition.Aligned(Alignment.BottomEnd)
            )

            val trayController = rememberTrayWindowController(windowState)

            AppTray(
                controller = trayController,
                onMute = { say.clearQueue() },
                onExit = ::exitApplication,
            )

            Window(
                onCloseRequest = ::exitApplication,
                visible = trayController.isWindowVisible,
                title = org.jetbrains.compose.resources.stringResource(Res.string.app_name),
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
                        onCloseWindow = ::exitApplication,
                        onHideWindow = trayController::hideToTray,
                        onMinimizeWindow = { windowState.isMinimized = true }
                    )
                }
            }
        }
    }
    exitProcess(0)
}

private fun logStartupPlatformInfo() {
    startupLog.info(
        "Startup platform: os.name='{}', os.version='{}', os.arch='{}', java.version='{}', java.runtime.version='{}'",
        System.getProperty("os.name").orEmpty(),
        System.getProperty("os.version").orEmpty(),
        System.getProperty("os.arch").orEmpty(),
        System.getProperty("java.version").orEmpty(),
        System.getProperty("java.runtime.version").orEmpty(),
    )
}
