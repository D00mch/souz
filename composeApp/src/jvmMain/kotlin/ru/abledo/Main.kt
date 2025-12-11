package ru.abledo

import abledo.composeapp.generated.resources.Res
import abledo.composeapp.generated.resources.iconT
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
import org.kodein.di.compose.withDI
import ru.abledo.di.mainDiModule

fun main() {
    // Rm from dock (app is agent now); should be first line
    System.setProperty("apple.awt.UIElement", "true")

    application {
        withDI(mainDiModule) {
            var isWindowVisible by remember { mutableStateOf(true) }

            Tray(
                icon = painterResource(Res.drawable.iconT),
                tooltip = "Abledo AI",
                onAction = { isWindowVisible = !isWindowVisible },
                menu = {
                    Item("Показать/Скрыть", onClick = { isWindowVisible = !isWindowVisible })
                    Separator()
                    Item("Выход", onClick = ::exitApplication)
                }
            )

            val initialWidth = 500.dp
            val initialHeight = 260.dp

            val windowState = rememberWindowState(
                width = initialWidth,
                height = initialHeight,
                position = WindowPosition.Aligned(Alignment.BottomEnd)
            )

            Window(
                onCloseRequest = { isWindowVisible = false },
                visible = isWindowVisible, // Привязываем видимость к переменной
                title = "Abledo AI",
                state = windowState,
                transparent = true,
                undecorated = true,
                resizable = true,
                alwaysOnTop = true
            ) {
                WindowDraggableArea(modifier = Modifier.fillMaxSize()) {
                    App(
                        onWindowResize = { targetSize ->
                            val currentSize = windowState.size
                            val currentPos = windowState.position

                            // Resize logic
                            if (currentPos is WindowPosition.Absolute) {
                                val widthDelta = targetSize.width - currentSize.width
                                val heightDelta = targetSize.height - currentSize.height

                                val newX = currentPos.x - widthDelta
                                val newY = currentPos.y - heightDelta

                                windowState.position = WindowPosition.Absolute(newX, newY)
                            }

                            windowState.size = targetSize
                        },
                        onCloseWindow = { isWindowVisible = false }
                    )
                }
            }
        }
    }
}