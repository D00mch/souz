package ru.gigadesk

import gigadesk.composeapp.generated.resources.Res
import gigadesk.composeapp.generated.resources.iconT
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.jetbrains.compose.resources.painterResource
import org.kodein.di.compose.localDI
import org.kodein.di.compose.withDI
import org.kodein.di.instance
import ru.gigadesk.audio.Say
import ru.gigadesk.db.SettingsProvider
import ru.gigadesk.di.mainDiModule
import kotlin.math.roundToInt

fun main() {
    System.setProperty("apple.awt.UIElement", "true")

    application {
        withDI(mainDiModule) {
            val di = localDI()
            val say: Say by di.instance()
            val settingsProvider: SettingsProvider by di.instance()
            var isWindowVisible by remember { mutableStateOf(true) }

            Tray(
                icon = painterResource(Res.drawable.iconT),
                tooltip = "gigadesk AI",
                onAction = { isWindowVisible = !isWindowVisible },
                menu = {
                    Item("Показать/Скрыть", onClick = { isWindowVisible = !isWindowVisible })
                    Separator()

                    Item("Выключить звук", onClick = {
                        say.stopPlayText()
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
                title = "gigadesk AI",
                state = windowState,
                transparent = true,
                undecorated = true,
                resizable = true,
                alwaysOnTop = true
            ) {
                WindowDraggableArea(modifier = Modifier.fillMaxSize()) {
                    App(
                        // TODO: unsued callback? How do I check the windows current size?
                        onWindowResize = { targetSize ->
                            windowState.size = targetSize
                            settingsProvider.initialWindowWidthDp = targetSize.width.value.roundToInt()
                            settingsProvider.initialWindowHeightDp = targetSize.height.value.roundToInt()
                        },
                        onCloseWindow = { isWindowVisible = false }
                    )
                }
            }
        }
    }
}
