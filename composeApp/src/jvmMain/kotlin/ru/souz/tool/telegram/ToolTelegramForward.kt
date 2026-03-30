package ru.souz.tool.telegram

import kotlinx.coroutines.runBlocking
import ru.souz.db.ConfigStore
import ru.souz.db.SettingsProvider
import ru.souz.db.SettingsProviderImpl
import ru.souz.llms.giga.gigaJsonMapper
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

class ToolTelegramForward(
    private val telegramService: TelegramService,
    private val chatSelectionBroker: TelegramChatSelectionBroker,
    private val permissionBroker: ToolPermissionBroker? = null,
) : ToolSetup<ToolTelegramForward.Input> {
    private val settingsProvider: SettingsProvider by lazy { SettingsProviderImpl(ConfigStore) }

    data class Input(
        @InputParamDescription("Source chat name")
        val fromChat: String,
        @InputParamDescription("Destination chat name")
        val toChat: String,
        @InputParamDescription("Message id to forward, or 'last'")
        val messageId: String = "last",
        @InputParamDescription("Set true only after explicit user confirmation when SafeMode is enabled")
        val confirmed: Boolean = false,
    )

    override val name: String = "ToolTelegramForward"
    override val description: String = "Forwards a Telegram message from one chat to another using chat cache fuzzy match."
    override val fewShotExamples: List<FewShotExample> = listOf(
        FewShotExample(
            request = "Перешли последнее сообщение из чата Работа в чат Личное",
            params = mapOf(
                "fromChat" to "Работа",
                "toChat" to "Личное",
                "messageId" to "last",
            )
        )
    )
    override val returnParameters: ReturnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "JSON with forwarded message details"),
        )
    )

    override fun invoke(input: Input): String = runBlocking { suspendInvoke(input) }

    override suspend fun suspendInvoke(input: Input): String {
        if (input.fromChat.isBlank()) {
            throw BadInputException("fromChat is required")
        }
        if (input.toChat.isBlank()) {
            throw BadInputException("toChat is required")
        }

        if (settingsProvider.safeModeEnabled && !input.confirmed) {
            throw BadInputException(
                "SafeMode enabled: ask user confirmation and repeat call with confirmed=true"
            )
        }

        val sourceChat = with(TelegramToolResolvers) {
            chatSelectionBroker.resolveTelegramChatCandidate(
                telegramService = telegramService,
                rawChatName = input.fromChat,
            )
        } ?: return TelegramToolResolvers.telegramSelectionCancelled.msg
        val targetChat = with(TelegramToolResolvers) {
            chatSelectionBroker.resolveTelegramChatCandidate(
                telegramService = telegramService,
                rawChatName = input.toChat,
            )
        } ?: return TelegramToolResolvers.telegramSelectionCancelled.msg

        val result = permissionBroker?.requestPermission(
            getString(Res.string.permission_telegram_forward),
            linkedMapOf(
                "fromChat" to sourceChat.title,
                "toChat" to targetChat.title,
                "messageId" to input.messageId,
            )
        )
        if (result is ToolPermissionResult.No) return result.msg

        val forwarded = runCatching {
            telegramService.forwardMessageByChatIds(sourceChat.chatId, targetChat.chatId, input.messageId)
        }.getOrElse { error ->
            throw BadInputException(error.message ?: "Failed to forward Telegram message")
        }

        return gigaJsonMapper.writeValueAsString(
            mapOf(
                "status" to "forwarded",
                "chatId" to forwarded.chatId,
                "chatTitle" to forwarded.chatTitle,
                "messageId" to forwarded.messageId,
                "text" to forwarded.text,
                "time" to forwarded.unixTime,
            )
        )
    }
}
