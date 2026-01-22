package ru.gigadesk.ui.main

import ru.gigadesk.agent.engine.AgentContext
import ru.gigadesk.ui.VMEvent
import ru.gigadesk.ui.VMSideEffect
import ru.gigadesk.ui.VMState

/**
 * State for the main screen that mirrors the floating glass panel experience.
 */
data class MainState(
    val displayedText: String = randomStatusTip,
    val isListening: Boolean = false,
    val statusMessage: String = "",
    val lastText: String? = null,
    val lastKnownAgentContext: AgentContext<String>? = null,
    val userExpectCloseOnX: Boolean = false,
    val isProcessing: Boolean = false,
) : VMState {

    companion object {
        val START_TIPS = listOf(
            "Погода в Москве.",
            "Спроси, когда у тебя следующая встреча.",
            "Хочешь, я проверю почту?",
            "Найти файл, прочитать его, создать новый?",
            "Нужно отправить письмо или ответить?",
            "Показать непрочитанные в почте?",
            "Создать или отменить встречу в календаре?",
            "Показать список дел на сегодня?",
            "Вернуть закрытую вкладку браузера?",
            "Найти сайт в истории Safari или Chrome?",
            "Построить график из CSV-файла?",
            "Открыть заметку или создать новую?",
            "Найти текст внутри файлов?",
            "Удалить лишний файл?",
            "Отправить сообщение в Telegram?",
            "Сохранить инструкцию для ассистента?",
            "Прочитать письмо и подготовить ответ?",
            "Можешь выделить и попросить переписать",
        )

        private val randomStatusTip: String = START_TIPS.random()

        fun randomStatusTip(): String = START_TIPS.random()
    }
}

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
