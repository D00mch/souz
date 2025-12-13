package ru.abledo.ui.main

import ru.abledo.agent.engine.AgentContext
import ru.abledo.ui.VMEvent
import ru.abledo.ui.VMSideEffect
import ru.abledo.ui.VMState

/**
 * State for the main screen that mirrors the floating glass panel experience.
 */
data class MainState(
    val displayedText: String,
    val isListening: Boolean = false,
    val statusMessage: String = randomStatusTip(),
    val lastText: String? = null,
    val lastKnownAgentContext: AgentContext<String>? = null,
    val userExpectCloseOnX: Boolean = false,
) : VMState

    companion object {
        private val START_TIPS = listOf(
            "Погода в Москве.",
            "Попроси меня, и я скажу, когда у тебя следующая встреча.",
            "Хочешь, я проверю почту?",
            "Найти файл, прочитать его или создать новый?",
            "Открыть приложение или показать запущенные окна?",
            "Нужно отправить письмо или ответить на входящее?",
            "Найти письмо в почте или показать непрочитанные?",
            "Создать событие в календаре или отменить встречу?",
            "Показать список дел на сегодня?",
            "Создать новую вкладку или переключиться на нужную в браузере?",
            "Найти вкладку в Safari или Chrome?",
            "Построить график из CSV-файла?",
            "Загрузить файл или скачать результат?",
            "Открыть заметку или создать новую?",
            "Найти текст внутри файлов?",
            "Удалить лишний файл?",
            "Отправить сообщение в Telegram?",
            "Изменить настройки звука?",
            "Сохранить инструкцию для ассистента?",
            "Показать горячие клавиши в браузере?",
            "Прочитать письмо и подготовить ответ?"
        )

        fun randomStatusTip(): String = START_TIPS.random()
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
