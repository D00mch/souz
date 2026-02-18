package ru.souz.ui.common

import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.Composable
import ru.souz.LocalWindowScope

@Composable
fun DraggableWindowArea(
    content: @Composable () -> Unit
) {
    val windowScope = LocalWindowScope.current
    
    if (windowScope != null) {
        with(windowScope) {
            WindowDraggableArea {
                content()
            }
        }
    } else {
        // Fallback if no WindowScope available
        content()
    }
}
