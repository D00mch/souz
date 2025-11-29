package ru.abledo

import androidx.compose.foundation.layout.Box
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
        // Настраиваем положение и размер
        val windowState = rememberWindowState(
            width = 500.dp,
            height = 250.dp,
            position = WindowPosition.Aligned(Alignment.BottomEnd)
        )

        Window(
            onCloseRequest = ::exitApplication,
            title = "Abledo AI",
            state = windowState,
            transparent = true,
            undecorated = true,
            resizable = true, // Лучше запретить ресайз, чтобы не ломать верстку шара
            alwaysOnTop = true
        ) {
            // DraggableArea позволяет таскать окно, если оно не там встало
            WindowDraggableArea(modifier = Modifier.fillMaxSize()) {
                App()
            }
        }
    }
}