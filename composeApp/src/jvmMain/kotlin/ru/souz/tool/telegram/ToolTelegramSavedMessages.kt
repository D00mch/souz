package ru.souz.tool.telegram

import kotlinx.coroutines.runBlocking
import ru.souz.giga.gigaJsonMapper
import ru.souz.service.telegram.TelegramService
import ru.souz.tool.BadInputException
import ru.souz.tool.FewShotExample
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolSetup

class ToolTelegramSavedMessages(
    private val telegramService: TelegramService,
) : ToolSetup<ToolTelegramSavedMessages.Input> {

    data class Input(
        @InputParamDescription("Text to save into Telegram Saved Messages")
        val text: String,
    )

    override val name: String = "ToolTelegramSavedMessages"
    override val description: String = "Saves text to Telegram Saved Messages (chat with self)."
    override val fewShotExamples: List<FewShotExample> = listOf(
        FewShotExample(
            request = "Сохрани в Избранное Telegram: созвон в 16:30",
            params = mapOf("text" to "Созвон в 16:30"),
        )
    )
    override val returnParameters: ReturnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "JSON with sent message details"),
        )
    )

    override fun invoke(input: Input): String = runBlocking { suspendInvoke(input) }

    override suspend fun suspendInvoke(input: Input): String {
        if (input.text.isBlank()) {
            throw BadInputException("text is required")
        }

        val sent = runCatching {
            telegramService.sendToSavedMessages(input.text)
        }.getOrElse { error ->
            throw BadInputException(error.message ?: "Failed to write to Telegram Saved Messages")
        }

        return gigaJsonMapper.writeValueAsString(
            mapOf(
                "status" to "saved",
                "chatId" to sent.chatId,
                "chatTitle" to sent.chatTitle,
                "messageId" to sent.messageId,
                "text" to sent.text,
                "time" to sent.unixTime,
            )
        )
    }
}
