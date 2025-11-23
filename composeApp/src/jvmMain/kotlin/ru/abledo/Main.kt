package ru.abledo

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import org.kodein.di.compose.withDI
import org.slf4j.LoggerFactory
import ru.abledo.di.mainDiModule

private val l = LoggerFactory.getLogger("AI")


fun main() = application {
    withDI(mainDiModule) {
        Window(
            onCloseRequest = ::exitApplication,
            title = "TestJvmSize",
        ) {
            App()
        }
    }
}
