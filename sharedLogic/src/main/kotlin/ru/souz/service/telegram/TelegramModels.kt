package ru.souz.service.telegram

enum class TelegramAuthStep {
    INITIALIZING,
    WAIT_PHONE,
    WAIT_CODE,
    WAIT_PASSWORD,
    READY,
    LOGGING_OUT,
    CLOSED,
    ERROR,
}

enum class BotTaskType { NONE, CREATE, DELETE }

enum class BotCreationStep {
    NONE, INIT, NAME, USERNAME, WAIT_TOKEN, START, AVATAR_CMD, AVATAR_MOCK, AVATAR_PIC, SETCMDS_CMD, SETCMDS_BOT, SETCMDS_LIST, FINISHED
}

enum class BotDeletionStep {
    NONE, INIT, WAIT_NO_BOTS, USERNAME, WAIT_DELETION, FINISHED
}

data class TelegramAuthState(
    val step: TelegramAuthStep = TelegramAuthStep.INITIALIZING,
    val activePhoneMasked: String? = null,
    val codeHint: String? = null,
    val passwordHint: String? = null,
    val isBusy: Boolean = false,
    val errorMessage: String? = null,
)

data class TelegramCachedContact(
    val userId: Long,
    val displayName: String,
    val aliases: Set<String>,
)

data class TelegramContactCandidate(
    val userId: Long,
    val displayName: String,
    val username: String?,
    val phoneMasked: String?,
    val isContact: Boolean,
    val chatId: Long?,
    val lastMessageText: String?,
    val score: Int,
)

sealed interface TelegramContactLookupResult {
    data class Resolved(val candidate: TelegramContactCandidate) : TelegramContactLookupResult
    data class Ambiguous(
        val query: String,
        val candidates: List<TelegramContactCandidate>,
    ) : TelegramContactLookupResult

    data class NotFound(val query: String) : TelegramContactLookupResult
}

data class TelegramCachedChat(
    val chatId: Long,
    val title: String,
    val unreadCount: Int,
    val lastMessageId: Long,
    val lastMessageText: String?,
    val order: Long,
    val linkedUserId: Long?,
)

data class TelegramChatCandidate(
    val chatId: Long,
    val title: String,
    val unreadCount: Int,
    val linkedUserId: Long?,
    val lastMessageText: String?,
    val score: Int,
)

sealed interface TelegramChatLookupResult {
    data class Resolved(val candidate: TelegramChatCandidate) : TelegramChatLookupResult
    data class Ambiguous(
        val query: String,
        val candidates: List<TelegramChatCandidate>,
    ) : TelegramChatLookupResult

    data class NotFound(val query: String) : TelegramChatLookupResult
}

data class TelegramInboxItem(
    val chatId: Long,
    val title: String,
    val unreadCount: Int,
    val lastText: String?,
)

data class TelegramMessageView(
    val chatId: Long,
    val chatTitle: String,
    val messageId: Long,
    val sender: String?,
    val unixTime: Long,
    val text: String?,
)

enum class TelegramChatAction {
    Mute,
    Archive,
    MarkRead,
    Delete,
}
