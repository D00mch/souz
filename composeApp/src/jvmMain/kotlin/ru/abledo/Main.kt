package ru.abledo

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.kodein.di.compose.withDI
import org.slf4j.LoggerFactory
import ru.abledo.di.mainDiModule

private val l = LoggerFactory.getLogger("AI")

fun main() {
    // 1. Убираем иконку из Дока (делаем приложение "агентом")
    // Это должно быть самой первой строчкой
    System.setProperty("apple.awt.UIElement", "true")

    application {
        withDI(mainDiModule) {
            // Состояние: видно окно или нет
            var isWindowVisible by remember { mutableStateOf(true) }

            // 2. Иконка в Трее (верхний бар macOS)
            Tray(
                // ВАЖНО: Положи файл icon.png (16x16 или 32x32) в src/main/resources
                icon = painterResource("iconT.png"),
                tooltip = "Abledo AI",
                // По клику на иконку переключаем видимость окна
                onAction = { isWindowVisible = !isWindowVisible },
                menu = {
                    Item("Показать/Скрыть", onClick = { isWindowVisible = !isWindowVisible })
                    Separator()
                    Item("Выход", onClick = ::exitApplication)
                }
            )

            // Стартовые размеры
            val initialWidth = 500.dp
            val initialHeight = 260.dp

            val windowState = rememberWindowState(
                width = initialWidth,
                height = initialHeight,
                position = WindowPosition.Aligned(Alignment.BottomEnd)
            )

            // 3. Само Окно
            Window(
                // Вместо выхода из приложения просто скрываем окно
                onCloseRequest = { isWindowVisible = false },
                visible = isWindowVisible, // Привязываем видимость к переменной
                title = "Abledo AI",
                state = windowState,
                transparent = true,
                undecorated = true,
                resizable = false,
                alwaysOnTop = true
            ) {
                WindowDraggableArea(modifier = Modifier.fillMaxSize()) {
                    App(
                        onWindowResize = { targetSize ->
                            val currentSize = windowState.size
                            val currentPos = windowState.position

                            // Твоя логика "роста" окна влево и вверх
                            if (currentPos is WindowPosition.Absolute) {
                                val widthDelta = targetSize.width - currentSize.width
                                val heightDelta = targetSize.height - currentSize.height

                                val newX = currentPos.x - widthDelta
                                val newY = currentPos.y - heightDelta

                                windowState.position = WindowPosition.Absolute(newX, newY)
                            }

                            windowState.size = targetSize
                        }
                    )
                }
            }
        }
    }
}