package ru.abledo.ui.main

import ru.abledo.ui.VMEvent
import ru.abledo.ui.VMSideEffect
import ru.abledo.ui.VMState

/**
 * State for the main screen that mirrors the floating glass panel experience.
 */
data class MainState(
    val displayedText: String = "От меня сейчас что требуется?",
    val isListening: Boolean = false,
    val statusMessage: String = "Ожидание горячей клавиши"
) : VMState

sealed interface MainEvent : VMEvent {
    object StartListening : MainEvent
    object StopListening : MainEvent
    object ClearContext : MainEvent
    object StopSpeech : MainEvent
}

sealed interface MainEffect : VMSideEffect {
    data class ShowError(val message: String) : MainEffect
}
