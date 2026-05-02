package ru.souz.backend.http

internal object BackendHttpRoutes {
    const val ROOT = "/"
    const val HEALTH = "/health"
    const val LEGACY_AGENT = "/agent"
    const val V1 = "/v1"
    const val BOOTSTRAP = "$V1/bootstrap"
    const val SETTINGS = "$V1/me/settings"
    const val PROVIDER_KEYS = "$V1/me/provider-keys"
    const val CHATS = "$V1/chats"
    const val OPTIONS = "$V1/options"

    private const val PROVIDER_PARAMETER = "{provider}"
    private const val CHAT_ID_PARAMETER = "{chatId}"
    private const val EXECUTION_ID_PARAMETER = "{executionId}"
    private const val OPTION_ID_PARAMETER = "{optionId}"

    const val PROVIDER_KEY_PATTERN = "$PROVIDER_KEYS/$PROVIDER_PARAMETER"
    const val CHAT_MESSAGES_PATTERN = "$CHATS/$CHAT_ID_PARAMETER/messages"
    const val CHAT_EVENTS_PATTERN = "$CHATS/$CHAT_ID_PARAMETER/events"
    const val CHAT_WS_PATTERN = "$CHATS/$CHAT_ID_PARAMETER/ws"
    const val CHAT_CANCEL_ACTIVE_PATTERN = "$CHATS/$CHAT_ID_PARAMETER/cancel-active"
    const val CHAT_EXECUTION_CANCEL_PATTERN =
        "$CHATS/$CHAT_ID_PARAMETER/executions/$EXECUTION_ID_PARAMETER/cancel"
    const val OPTION_ANSWER_PATTERN = "$OPTIONS/$OPTION_ID_PARAMETER/answer"

    fun providerKey(provider: String): String = "$PROVIDER_KEYS/$provider"

    fun chatMessages(chatId: Any): String = "$CHATS/$chatId/messages"

    fun chatEvents(chatId: Any): String = "$CHATS/$chatId/events"

    fun chatWebSocket(chatId: Any): String = "$CHATS/$chatId/ws"

    fun cancelActive(chatId: Any): String = "$CHATS/$chatId/cancel-active"

    fun cancelExecution(chatId: Any, executionId: Any): String =
        "$CHATS/$chatId/executions/$executionId/cancel"

    fun optionAnswer(optionId: Any): String = "$OPTIONS/$optionId/answer"

    fun isV1Path(path: String): Boolean = path == V1 || path.startsWith("$V1/")
}
