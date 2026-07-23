package ru.souz.backend.http

internal object BackendHttpRoutes {
    const val ROOT = "/"
    const val HEALTH = "/health"
    const val DOCS = "/docs"
    const val OPENAPI_DOCUMENT = "$DOCS/openapi.json"
    const val V1 = "/v1"
    const val BOOTSTRAP = "$V1/bootstrap"
    const val ONBOARDING_STATE = "$V1/onboarding/state"
    const val ONBOARDING_COMPLETE = "$V1/onboarding/complete"
    const val SETTINGS = "$V1/me/settings"
    const val PROVIDER_KEYS = "$V1/me/provider-keys"
    const val CHATS = "$V1/chats"
    const val OPTIONS = "$V1/options"
    const val PERMISSION_REQUESTS = "$V1/permission-requests"

    private const val PROVIDER_PARAMETER = "{provider}"
    private const val CHAT_ID_PARAMETER = "{chatId}"
    private const val EXECUTION_ID_PARAMETER = "{executionId}"
    private const val OPTION_ID_PARAMETER = "{optionId}"
    private const val PERMISSION_REQUEST_ID_PARAMETER = "{id}"

    const val PROVIDER_KEY_PATTERN = "$PROVIDER_KEYS/$PROVIDER_PARAMETER"
    const val CHAT_TITLE_PATTERN = "$CHATS/$CHAT_ID_PARAMETER/title"
    const val CHAT_ARCHIVE_PATTERN = "$CHATS/$CHAT_ID_PARAMETER/archive"
    const val CHAT_UNARCHIVE_PATTERN = "$CHATS/$CHAT_ID_PARAMETER/unarchive"
    const val CHAT_MESSAGES_PATTERN = "$CHATS/$CHAT_ID_PARAMETER/messages"
    const val CHAT_TELEGRAM_BOT_PATTERN = "$CHATS/$CHAT_ID_PARAMETER/telegram-bot"
    const val CHAT_EVENTS_PATTERN = "$CHATS/$CHAT_ID_PARAMETER/events"
    const val CHAT_WS_PATTERN = "$CHATS/$CHAT_ID_PARAMETER/ws"
    const val CHAT_CANCEL_ACTIVE_PATTERN = "$CHATS/$CHAT_ID_PARAMETER/cancel-active"
    const val CHAT_PENDING_PERMISSION_REQUESTS_PATTERN =
        "$CHATS/$CHAT_ID_PARAMETER/permission-requests/pending"
    const val CHAT_EXECUTION_CANCEL_PATTERN =
        "$CHATS/$CHAT_ID_PARAMETER/executions/$EXECUTION_ID_PARAMETER/cancel"
    const val OPTION_ANSWER_PATTERN = "$OPTIONS/$OPTION_ID_PARAMETER/answer"
    const val PERMISSION_DECISION_PATTERN =
        "$PERMISSION_REQUESTS/$PERMISSION_REQUEST_ID_PARAMETER/decision"

    fun providerKey(provider: String): String = "$PROVIDER_KEYS/$provider"

    fun chatMessages(chatId: Any): String = "$CHATS/$chatId/messages"

    fun chatTelegramBot(chatId: Any): String = "$CHATS/$chatId/telegram-bot"

    fun chatTitle(chatId: Any): String = "$CHATS/$chatId/title"

    fun archiveChat(chatId: Any): String = "$CHATS/$chatId/archive"

    fun unarchiveChat(chatId: Any): String = "$CHATS/$chatId/unarchive"

    fun chatEvents(chatId: Any): String = "$CHATS/$chatId/events"

    fun chatWebSocket(chatId: Any): String = "$CHATS/$chatId/ws"

    fun cancelActive(chatId: Any): String = "$CHATS/$chatId/cancel-active"

    fun cancelExecution(chatId: Any, executionId: Any): String =
        "$CHATS/$chatId/executions/$executionId/cancel"

    fun optionAnswer(optionId: Any): String = "$OPTIONS/$optionId/answer"

    fun pendingPermissionRequests(chatId: Any): String =
        "$CHATS/$chatId/permission-requests/pending"

    fun permissionDecision(permissionRequestId: Any): String =
        "$PERMISSION_REQUESTS/$permissionRequestId/decision"

    fun isV1Path(path: String): Boolean = path == V1 || path.startsWith("$V1/")
}
