package ru.souz.tool.telegram

import ru.souz.service.telegram.TelegramContactCandidate
import ru.souz.service.telegram.TelegramContactLookupResult
import ru.souz.service.telegram.TelegramChatCandidate
import ru.souz.service.telegram.TelegramChatLookupResult
import ru.souz.service.telegram.TelegramService
import ru.souz.tool.BadInputException
import ru.souz.tool.ToolPermissionResult

internal object TelegramToolResolvers {
    private const val USER_DISAPPROVED_MESSAGE = "User disapproved"
    val telegramSelectionCancelled = ToolPermissionResult.No(USER_DISAPPROVED_MESSAGE)

    suspend fun TelegramChatSelectionBroker.resolveTelegramChatCandidate(
        telegramService: TelegramService,
        rawChatName: String,
    ): TelegramChatCandidate? {
        return when (val resolution = telegramService.resolveChatTarget(rawChatName)) {
            is TelegramChatLookupResult.Resolved -> resolution.candidate
            is TelegramChatLookupResult.Ambiguous -> {
                val selectedChatId = requestSelection(
                    query = rawChatName,
                    candidates = resolution.candidates,
                )
                if (selectedChatId == null) {
                    return null
                }
                resolution.candidates.firstOrNull { it.chatId == selectedChatId }
                    ?: throw BadInputException("Selected Telegram chat is no longer available")
            }

            is TelegramChatLookupResult.NotFound ->
                throw BadInputException("Chat '${resolution.query}' not found in Telegram cache")
        }
    }

    suspend fun TelegramContactSelectionBroker.resolveTelegramContactCandidate(
        telegramService: TelegramService,
        rawTargetName: String,
    ): TelegramContactCandidate? {
        return when (val resolution = telegramService.resolveContactTarget(rawTargetName)) {
            is TelegramContactLookupResult.Resolved -> resolution.candidate
            is TelegramContactLookupResult.Ambiguous -> {
                val selectedUserId = requestSelection(
                    query = rawTargetName,
                    candidates = resolution.candidates,
                )
                if (selectedUserId == null) {
                    return null
                }
                resolution.candidates.firstOrNull { it.userId == selectedUserId }
                    ?: throw BadInputException("Selected Telegram contact is no longer available")
            }

            is TelegramContactLookupResult.NotFound ->
                throw BadInputException("Contact '${resolution.query}' not found in Telegram cache")
        }
    }
}
