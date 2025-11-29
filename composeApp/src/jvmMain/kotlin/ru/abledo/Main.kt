package ru.abledo

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.kodein.di.compose.withDI
import org.slf4j.LoggerFactory
import ru.abledo.di.mainDiModule

private val l = LoggerFactory.getLogger("AI")

fun main() = application {
    withDI(mainDiModule) {
        // Стартовые размеры (совпадают с CardMinHeight/Width из MainScreen)
        val initialWidth = 500.dp
        val initialHeight = 260.dp

        val windowState = rememberWindowState(
            width = initialWidth,
            height = initialHeight,
            // Изначально ставим в правый нижний угол
            position = WindowPosition.Aligned(Alignment.BottomEnd)
        )

        Window(
            onCloseRequest = ::exitApplication,
            title = "Abledo AI",
            state = windowState,
            transparent = true,
            undecorated = true,
            resizable = false, // Запрещаем ручной ресайз мышкой
            alwaysOnTop = true
        ) {
            WindowDraggableArea(modifier = Modifier.fillMaxSize()) {
                App(
                    onWindowResize = { targetSize ->
                        val currentSize = windowState.size
                        val currentPos = windowState.position

                        // Проверяем, задана ли позиция в абсолютных координатах (X, Y).
                        // Compose переводит Aligned в Absolute после первого рендера.
                        if (currentPos is WindowPosition.Absolute) {

                            // Считаем разницу: насколько окно хочет вырасти?
                            val widthDelta = targetSize.width - currentSize.width
                            val heightDelta = targetSize.height - currentSize.height

                            // ЛОГИКА "ПРИКЛЕЕННОГО" ПРАВОГО НИЖНЕГО УГЛА:

                            // 1. Если ширина растет (delta > 0), сдвигаем X влево (-delta).
                            //    Если сжимается (delta < 0), сдвигаем X вправо.
                            val newX = currentPos.x - widthDelta

                            // 2. Если высота растет (delta > 0), сдвигаем Y вверх (-delta).
                            //    Если сжимается (delta < 0), сдвигаем Y вниз.
                            val newY = currentPos.y - heightDelta

                            // Применяем новую позицию ОДНОВРЕМЕННО с размером,
                            // чтобы избежать мерцания (насколько это возможно в Swing).
                            windowState.position = WindowPosition.Absolute(newX, newY)
                        }

                        // Применяем новый размер
                        windowState.size = targetSize
                    }
                )
            }
        }
    }
}