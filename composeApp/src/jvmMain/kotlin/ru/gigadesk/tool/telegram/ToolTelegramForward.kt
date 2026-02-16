package ru.gigadesk.tool.telegram

import kotlinx.coroutines.runBlocking
import ru.gigadesk.db.ConfigStore
import ru.gigadesk.db.SettingsProvider
import ru.gigadesk.db.SettingsProviderImpl
import ru.gigadesk.giga.gigaJsonMapper
import ru.gigadesk.service.telegram.TelegramService
import ru.gigadesk.tool.BadInputException
import ru.gigadesk.tool.FewShotExample
import ru.gigadesk.tool.InputParamDescription
import ru.gigadesk.tool.ReturnParameters
import ru.gigadesk.tool.ReturnProperty
import ru.gigadesk.tool.ToolSetup

class ToolTelegramForward(
    private val telegramService: TelegramService,
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

        val forwarded = runCatching {
            telegramService.forwardMessage(input.fromChat, input.toChat, input.messageId)
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
