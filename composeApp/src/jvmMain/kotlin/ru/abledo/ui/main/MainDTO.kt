package ru.abledo.ui.main

import ru.abledo.ui.VMEvent
import ru.abledo.ui.VMSideEffect
import ru.abledo.ui.VMState

/**
 * State for the main screen that mirrors the floating glass panel experience.
 */
data class MainState(
    val displayedText: String,
    val isListening: Boolean = false,
    val statusMessage: String = "Ожидание горячей клавиши",
    val lastText: String? = null,
    val userExpectCloseOnX: Boolean = false
) : VMState

sealed interface MainEvent : VMEvent {
    object StartListening : MainEvent
    object StopListening : MainEvent
    object ClearContext : MainEvent
    object StopSpeech : MainEvent
    object ShowLastText : MainEvent
}

sealed interface MainEffect : VMSideEffect {
    data class ShowError(val message: String) : MainEffect
    object Hide : MainEffect
}
