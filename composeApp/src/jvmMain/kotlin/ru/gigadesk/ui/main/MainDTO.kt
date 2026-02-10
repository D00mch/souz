package ru.gigadesk.ui.main

import ru.gigadesk.agent.engine.AgentContext
import ru.gigadesk.ui.VMEvent
import ru.gigadesk.ui.VMSideEffect
import ru.gigadesk.ui.VMState
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Chat message for the chat mode.
 */
data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val id: String = java.util.UUID.randomUUID().toString()
)

data class ToolPermissionDialogData(
    val requestId: Long,
    val description: String,
    val params: Map<String, String>,
)

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
    val agentHistory: List<ru.gigadesk.giga.GigaRequest.Message> = emptyList(),
    val isThinkingPanelOpen: Boolean = false,
    val isChatMode: Boolean = false,
    val chatMessages: List<ChatMessage> = emptyList(),
    val chatInputText: TextFieldValue = TextFieldValue(""),
    val toolPermissionDialog: ToolPermissionDialogData? = null,
) : VMState {

    companion object {
        val START_TIPS = listOf(
            "Хочешь узнаю погоду в Москве?",
            "Посмотреть календарь?",
            "Хочешь, я проверю почту?",
            "Найти файл, прочитать его или создать новый?",
            "Нужно отправить письмо или ответить?",
            "Показать непрочитанные в почте?",
            "Создать или отменить встречу в календаре?",
            "Показать список дел на сегодня?",
            "Вернуть закрытую вкладку браузера?",
            "Найти сайт в истории Safari или Chrome?",
            "Открыть заметку или создать новую?",
            "Удалить лишний файл?",
            "Прочитать письмо и подготовить ответ?",
            "Помочь в работе с текстом?",
        )

        private val randomStatusTip: String = START_TIPS.random()

        fun randomStatusTip(): String = START_TIPS.random()
    }
}

sealed interface MainEvent : VMEvent {
    data object StartListening : MainEvent
    data object StopListening : MainEvent
    data object ClearContext : MainEvent
    data object StopSpeech : MainEvent
    data object ShowLastText : MainEvent
    data object ToggleThinkingPanel : MainEvent
    data object ToggleChatMode : MainEvent
    data class UpdateChatInput(val text: TextFieldValue) : MainEvent
    data object SendChatMessage : MainEvent
    data object ApproveToolPermission : MainEvent
    data object RejectToolPermission : MainEvent
}

sealed interface MainEffect : VMSideEffect {
    data class ShowError(val message: String) : MainEffect
    object Hide : MainEffect
}
