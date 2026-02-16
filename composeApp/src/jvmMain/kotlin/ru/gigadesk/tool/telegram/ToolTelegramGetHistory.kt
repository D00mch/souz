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

class ToolTelegramGetHistory(
    private val telegramService: TelegramService,
) : ToolSetup<ToolTelegramGetHistory.Input> {

    data class Input(
        @InputParamDescription("Chat name for fuzzy lookup in Telegram chat cache")
        val chatName: String,
        @InputParamDescription("How many recent messages to return")
        val limit: Int = 30,
    )

    override val name: String = "ToolTelegramGetHistory"
    override val description: String = "Gets Telegram chat history by fuzzy chat name match for summarization."
    override val fewShotExamples: List<FewShotExample> = listOf(
        FewShotExample(
            request = "Покажи последние 25 сообщений из чата Проект Альфа",
            params = mapOf(
                "chatName" to "Проект Альфа",
                "limit" to 25,
            )
        )
    )
    override val returnParameters: ReturnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "JSON with messages suitable for summarization"),
        )
    )

    override fun invoke(input: Input): String = runBlocking { suspendInvoke(input) }

    override suspend fun suspendInvoke(input: Input): String {
        if (input.chatName.isBlank()) {
            throw BadInputException("chatName is required")
        }

        val history = runCatching {
            telegramService.getHistory(input.chatName, input.limit)
        }.getOrElse { error ->
            throw BadInputException(error.message ?: "Failed to fetch Telegram history")
        }

        val summaryReady = history.map { msg ->
            val sender = msg.sender ?: "Unknown"
            val text = msg.text?.trim().orEmpty().ifBlank { "<non-text message>" }
            "[$sender] $text"
        }

        return gigaJsonMapper.writeValueAsString(
            mapOf(
                "count" to history.size,
                "chatId" to history.firstOrNull()?.chatId,
                "chatTitle" to history.firstOrNull()?.chatTitle,
                "messages" to history.map { msg ->
                    mapOf(
                        "messageId" to msg.messageId,
                        "sender" to msg.sender,
                        "time" to msg.unixTime,
                        "text" to msg.text,
                    )
                },
                "summaryReady" to summaryReady,
            )
        )
    }
}
