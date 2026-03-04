package ru.souz.tool.telegram

import kotlinx.coroutines.runBlocking
import ru.souz.db.ConfigStore
import ru.souz.db.SettingsProvider
import ru.souz.db.SettingsProviderImpl
import ru.souz.giga.gigaJsonMapper
import ru.souz.service.telegram.TelegramChatAction
import ru.souz.service.telegram.TelegramService
import ru.souz.tool.BadInputException
import ru.souz.tool.FewShotExample
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolPermissionBroker
import ru.souz.tool.ToolPermissionResult
import ru.souz.tool.ToolSetup
import souz.composeapp.generated.resources.Res
import souz.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

class ToolTelegramSetState(
    private val telegramService: TelegramService,
    private val chatSelectionBroker: TelegramChatSelectionBroker? = null,
    private val permissionBroker: ToolPermissionBroker? = null,
) : ToolSetup<ToolTelegramSetState.Input> {
    private val settingsProvider: SettingsProvider by lazy { SettingsProviderImpl(ConfigStore) }

    enum class Action {
        Mute,
        Archive,
        MarkRead,
        Delete,
    }

    data class Input(
        @InputParamDescription("Chat name for fuzzy lookup")
        val chatName: String,
        @InputParamDescription("Action to apply: Mute, Archive, MarkRead, Delete")
        val action: Action,
        @InputParamDescription("Set true only after explicit user confirmation when SafeMode is enabled")
        val confirmed: Boolean = false,
    )

    override val name: String = "ToolTelegramSetState"
    override val description: String = "Apply Telegram chat state action: mute/archive/mark-read/delete."
    override val fewShotExamples: List<FewShotExample> = listOf(
        FewShotExample(
            request = "Отправь чат 'Работа' в архив",
            params = mapOf("chatName" to "Работа", "action" to "Archive"),
        ),
        FewShotExample(
            request = "Пометь чат 'Вася' как прочитанный",
            params = mapOf("chatName" to "Вася", "action" to "MarkRead"),
        )
    )
    override val returnParameters: ReturnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "JSON with updated chat state"),
        )
    )

    override fun invoke(input: Input): String = runBlocking { suspendInvoke(input) }

    override suspend fun suspendInvoke(input: Input): String {
        if (input.chatName.isBlank()) {
            throw BadInputException("chatName is required")
        }

        if (settingsProvider.safeModeEnabled && !input.confirmed) {
            throw BadInputException(
                "SafeMode enabled: ask user confirmation and repeat call with confirmed=true"
            )
        }

        val chatCandidate = resolveTelegramChatCandidate(
            telegramService = telegramService,
            selectionBroker = chatSelectionBroker,
            rawChatName = input.chatName,
        ) ?: return telegramSelectionCancelled.msg

        val result = permissionBroker?.requestPermission(
            getString(Res.string.permission_telegram_set_state),
            linkedMapOf(
                "chatName" to chatCandidate.title,
                "action" to input.action.name,
            )
        )
        if (result is ToolPermissionResult.No) return result.msg

        val action = when (input.action) {
            Action.Mute -> TelegramChatAction.Mute
            Action.Archive -> TelegramChatAction.Archive
            Action.MarkRead -> TelegramChatAction.MarkRead
            Action.Delete -> TelegramChatAction.Delete
        }

        val chat = runCatching {
            telegramService.setChatStateById(chatCandidate.chatId, action)
        }.getOrElse { error ->
            throw BadInputException(error.message ?: "Failed to apply Telegram chat action")
        }

        return gigaJsonMapper.writeValueAsString(
            mapOf(
                "status" to "ok",
                "action" to input.action.name,
                "chatId" to chat.chatId,
                "title" to chat.title,
                "unreadCount" to chat.unreadCount,
            )
        )
    }
}
