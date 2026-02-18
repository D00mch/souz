package ru.souz.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.Tray
import java.awt.Desktop
import java.awt.EventQueue
import java.awt.desktop.AppForegroundEvent
import java.awt.desktop.AppForegroundListener
import java.awt.desktop.AppHiddenEvent
import java.awt.desktop.AppHiddenListener
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import souz.composeapp.generated.resources.Res
import souz.composeapp.generated.resources.*

class TrayWindowController internal constructor(
    private val windowState: WindowState,
) {
    var isWindowVisible by mutableStateOf(true)
        private set
    private var isHiddenToTray by mutableStateOf(false)
    private var isAppHiddenBySystem by mutableStateOf(false)

    fun hideToTray() {
        isHiddenToTray = true
        isWindowVisible = false
    }

    fun toggleTrayVisibility() {
        if (isWindowVisible && !isAppHiddenBySystem) {
            hideToTray()
        } else {
            revealWindow()
        }
    }

    fun onAppRaisedToForeground() {
        if (isHiddenToTray) {
            isHiddenToTray = false
            isWindowVisible = true
            windowState.isMinimized = false
        }
    }

    fun onAppHiddenBySystem() {
        isAppHiddenBySystem = true
    }

    fun onAppUnhiddenBySystem() {
        isAppHiddenBySystem = false
    }

    private fun revealWindow() {
        isHiddenToTray = false
        isWindowVisible = true
        windowState.isMinimized = false
        requestAppForeground()
    }

    private fun requestAppForeground() {
        runCatching {
            if (!Desktop.isDesktopSupported()) return@runCatching
            val desktop = Desktop.getDesktop()
            if (desktop.isSupported(Desktop.Action.APP_REQUEST_FOREGROUND)) {
                desktop.requestForeground(true)
            }
        }
    }
}

@Composable
fun rememberTrayWindowController(windowState: WindowState): TrayWindowController {
    val controller = remember(windowState) { TrayWindowController(windowState) }

    DisposableEffect(controller) {
        val desktop = runCatching {
            if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
        }.getOrNull()

        if (desktop == null) {
            onDispose { }
        } else {
            val supportsForegroundEvents = desktop.isSupported(Desktop.Action.APP_EVENT_FOREGROUND)
            val supportsHiddenEvents = desktop.isSupported(Desktop.Action.APP_EVENT_HIDDEN)

            if (!supportsForegroundEvents && !supportsHiddenEvents) {
                onDispose { }
            } else {
                val listener = object : AppForegroundListener, AppHiddenListener {
                    override fun appRaisedToForeground(event: AppForegroundEvent) {
                        EventQueue.invokeLater {
                            controller.onAppRaisedToForeground()
                        }
                    }

                    override fun appMovedToBackground(event: AppForegroundEvent) = Unit

                    override fun appHidden(event: AppHiddenEvent) {
                        EventQueue.invokeLater {
                            controller.onAppHiddenBySystem()
                        }
                    }

                    override fun appUnhidden(event: AppHiddenEvent) {
                        EventQueue.invokeLater {
                            controller.onAppUnhiddenBySystem()
                        }
                    }
                }

                desktop.addAppEventListener(listener)

                onDispose {
                    runCatching { desktop.removeAppEventListener(listener) }
                }
            }
        }
    }

    return controller
}

@Composable
fun ApplicationScope.AppTray(
    controller: TrayWindowController,
    onMute: () -> Unit,
    onExit: () -> Unit,
) {
    Tray(
        icon = painterResource(Res.drawable.iconT),
        tooltip = stringResource(Res.string.tray_tooltip),
        onAction = controller::toggleTrayVisibility,
        menu = {
            Item(stringResource(Res.string.tray_show_hide), onClick = controller::toggleTrayVisibility)
            Separator()

            Item(stringResource(Res.string.tray_mute), onClick = onMute)
            Separator()

            Item(stringResource(Res.string.tray_exit), onClick = onExit)
        }
    )
}
