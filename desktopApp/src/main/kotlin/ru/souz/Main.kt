@file:OptIn(FlowPreview::class)

package ru.souz

import souz.sharedui.generated.resources.Res
import souz.sharedui.generated.resources.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.cancel
import kotlin.math.roundToInt
import kotlin.system.exitProcess
import org.slf4j.LoggerFactory
import org.kodein.di.bindSingleton
import org.kodein.di.compose.localDI
import org.kodein.di.compose.withDI
import org.kodein.di.instance
import ru.souz.agent.spi.AgentRuntimeEnvironment
import ru.souz.db.SettingsProvider
import ru.souz.di.DesktopAgentRuntimeEnvironment
import ru.souz.di.mainDiModule
import ru.souz.llms.local.LocalLlamaRuntime
import ru.souz.service.mcp.McpClientManager
import ru.souz.service.observability.DesktopStructuredLogger
import ru.souz.ui.host.DesktopSettingsHostPreferences
import ru.souz.ui.host.SettingsHostPreferences
import ru.souz.ui.rememberDockWindowController
import ru.souz.ui.macos.MacWindowVibrancy
import java.awt.Dimension
import java.awt.SystemColor.window
import java.util.concurrent.atomic.AtomicBoolean

import androidx.compose.ui.res.painterResource as jvmPainterResource

private val startupLog = LoggerFactory.getLogger("AppStartup")

fun main() {
    logStartupPlatformInfo()
    val settingsHostPreferences = DesktopSettingsHostPreferences()
    val agentRuntimeEnvironment = DesktopAgentRuntimeEnvironment(settingsHostPreferences.originalLocale)
    settingsHostPreferences.applyInterfaceLanguage()

    application(exitProcessOnExit = false) {
        withDI({
            import(mainDiModule, allowOverride = true)
            bindSingleton<SettingsHostPreferences>(overrides = true) { settingsHostPreferences }
            bindSingleton<AgentRuntimeEnvironment>(overrides = true) { agentRuntimeEnvironment }
        }) {
            val di = localDI()
            val settingsProvider: SettingsProvider by di.instance()
            val mcpClientManager: McpClientManager by di.instance()
            val telegramBotController: ru.souz.service.telegram.TelegramBotController by di.instance()
            val localLlamaRuntime: LocalLlamaRuntime by di.instance()
            val memoryMaintenanceBackgroundRunner: ru.souz.memory.DesktopMemoryMaintenanceBackgroundRunner by di.instance()
            val log: DesktopStructuredLogger by di.instance()
            val appScope: kotlinx.coroutines.CoroutineScope by di.instance()
            val closeServices: () -> Unit = remember(
                localLlamaRuntime,
                mcpClientManager,
                telegramBotController,
                log,
                appScope,
            ) {
                val closed = AtomicBoolean(false)
                ({
                    if (!closed.compareAndSet(false, true)) {
                        Unit
                    } else {
                        startupLog.info("Shutting down services")
                        runCatching { appScope.cancel() }
                            .onFailure { startupLog.warn("Failed to cancel app scope: {}", it.message) }
                        runCatching { localLlamaRuntime.close() }
                            .onFailure { startupLog.warn("Failed to close local runtime: {}", it.message) }
                        runCatching { mcpClientManager.close() }
                            .onFailure { startupLog.warn("Failed to close MCP manager: {}", it.message) }
                        runCatching { telegramBotController.close() }
                            .onFailure { startupLog.warn("Failed to close Telegram bot controller: {}", it.message) }
                        log.appClosed()
                    }
                })
            }

            DisposableEffect(Unit) {
                telegramBotController.start()
                val memoryMaintenanceJob = memoryMaintenanceBackgroundRunner.start()
                log.appOpened()
                val shutdownHook = Thread(Runnable { closeServices() }, "souz-shutdown-hook")
                Runtime.getRuntime().addShutdownHook(shutdownHook)

                onDispose {
                    memoryMaintenanceJob.cancel()
                    runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
                        .onFailure { error ->
                            if (error !is IllegalStateException) {
                                startupLog.warn("Failed to remove shutdown hook: {}", error.message)
                            }
                        }
                    closeServices()
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

            val minWindowWidthPx = 860
            val minWindowHeightPx = 680
            val maxWindowWidthPx = 896
            val maxWindowHeightPx = 700
            val initialWidth = settingsProvider.initialWindowWidthDp
                .coerceIn(minWindowWidthPx, maxWindowWidthPx)
                .dp
            val initialHeight = settingsProvider.initialWindowHeightDp
                .coerceIn(minWindowHeightPx, maxWindowHeightPx)
                .dp

            val windowState = rememberWindowState(
                width = initialWidth,
                height = initialHeight,
                position = WindowPosition.Aligned(Alignment.BottomEnd)
            )
            val isMacOs = remember { System.getProperty("os.name").contains("Mac", ignoreCase = true) }
            val windowController = rememberDockWindowController(windowState, enabled = isMacOs)
            val onWindowClose = if (isMacOs) windowController::hideWindow else ::exitApplication

            Window(
                onCloseRequest = onWindowClose,
                visible = windowController.isWindowVisible,
                title = key(settingsHostPreferences.useEnglishInterface) {
                    org.jetbrains.compose.resources.stringResource(Res.string.app_name)
                },
                icon = jvmPainterResource("icon-light.png"),
                state = windowState,
                transparent = true,
                undecorated = true,
                resizable = true,
                alwaysOnTop = false
            ) {
                LaunchedEffect(window) {
                    window.minimumSize = Dimension(minWindowWidthPx, minWindowHeightPx)
                    window.maximumSize = Dimension(maxWindowWidthPx, maxWindowHeightPx)
                    if (window.width !in minWindowWidthPx..maxWindowWidthPx ||
                        window.height !in minWindowHeightPx..maxWindowHeightPx
                    ) {
                        window.setSize(
                            window.width.coerceIn(minWindowWidthPx, maxWindowWidthPx),
                            window.height.coerceIn(minWindowHeightPx, maxWindowHeightPx)
                        )
                    }

                    // AWT peer can be unavailable on first frame; retry briefly.
                    repeat(10) {
                        if (MacWindowVibrancy.install(window)) {
                            return@LaunchedEffect
                        }
                        delay(80)
                    }
                }

                DisposableEffect(window) {
                    onDispose {
                        MacWindowVibrancy.uninstall(window)
                    }
                }

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
                        onCloseWindow = onWindowClose,
                        onMinimizeWindow = { windowState.isMinimized = true },
                        onToggleMaximizeWindow = {
                            windowState.placement = if (windowState.placement == WindowPlacement.Maximized) {
                                WindowPlacement.Floating
                            } else {
                                WindowPlacement.Maximized
                            }
                        }
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
