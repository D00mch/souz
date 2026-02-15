package ru.gigadesk.tool.telegram

import kotlinx.coroutines.runBlocking
import ru.gigadesk.giga.gigaJsonMapper
import ru.gigadesk.service.telegram.TelegramService
import ru.gigadesk.tool.BadInputException
import ru.gigadesk.tool.FewShotExample
import ru.gigadesk.tool.InputParamDescription
import ru.gigadesk.tool.ReturnParameters
import ru.gigadesk.tool.ReturnProperty
import ru.gigadesk.tool.ToolSetup

class ToolTelegramSend(
    private val telegramService: TelegramService,
) : ToolSetup<ToolTelegramSend.Input> {

    data class Input(
        @InputParamDescription("Target contact name (fuzzy match in Telegram contact cache)")
        val targetName: String,
        @InputParamDescription("Message text")
        val text: String,
    )

    override val name: String = "ToolTelegramSend"
    override val description: String = "Sends a Telegram message to a contact found via fuzzy contact cache lookup."
    override val fewShotExamples: List<FewShotExample> = listOf(
        FewShotExample(
            request = "Напиши Васе: буду через 10 минут",
            params = mapOf(
                "targetName" to "Вася",
                "text" to "Буду через 10 минут",
            )
        )
    )
    override val returnParameters: ReturnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "JSON with sent message details"),
        )
    )

    override fun invoke(input: Input): String = runBlocking { suspendInvoke(input) }

    override suspend fun suspendInvoke(input: Input): String {
        if (input.targetName.isBlank()) {
            throw BadInputException("targetName is required")
        }
        if (input.text.isBlank()) {
            throw BadInputException("text is required")
        }

        val sent = runCatching {
            telegramService.sendMessageToTarget(input.targetName, input.text)
        }.getOrElse { error ->
            throw BadInputException(error.message ?: "Failed to send Telegram message")
        }

        return gigaJsonMapper.writeValueAsString(
            mapOf(
                "status" to "sent",
                "chatId" to sent.chatId,
                "chatTitle" to sent.chatTitle,
                "messageId" to sent.messageId,
                "text" to sent.text,
                "time" to sent.unixTime,
            )
        )
    }
}
