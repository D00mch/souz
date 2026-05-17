package ru.souz.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.WindowState
import java.awt.Desktop
import java.awt.EventQueue
import java.awt.desktop.AppForegroundEvent
import java.awt.desktop.AppForegroundListener
import java.awt.desktop.AppReopenedEvent
import java.awt.desktop.AppReopenedListener

class DockWindowController internal constructor(
    private val windowState: WindowState,
) {
    var isWindowVisible by mutableStateOf(true)
        private set

    fun hideWindow() {
        isWindowVisible = false
    }

    fun onDockReopen() {
        if (!isWindowVisible || windowState.isMinimized) {
            revealWindow()
        }
    }

    private fun revealWindow() {
        isWindowVisible = true
        windowState.isMinimized = false
    }
}

@Composable
fun rememberDockWindowController(
    windowState: WindowState,
    enabled: Boolean,
): DockWindowController {
    val controller = remember(windowState) { DockWindowController(windowState) }

    DisposableEffect(controller, enabled) {
        if (!enabled) {
            onDispose { }
        } else {
            val desktop = runCatching {
                if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
            }.getOrNull()

            if (desktop == null) {
                onDispose { }
            } else {
                val listener = object : AppForegroundListener, AppReopenedListener {
                    override fun appRaisedToForeground(event: AppForegroundEvent) {
                        EventQueue.invokeLater {
                            controller.onDockReopen()
                        }
                    }

                    override fun appMovedToBackground(event: AppForegroundEvent) = Unit

                    override fun appReopened(event: AppReopenedEvent) {
                        EventQueue.invokeLater {
                            controller.onDockReopen()
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
