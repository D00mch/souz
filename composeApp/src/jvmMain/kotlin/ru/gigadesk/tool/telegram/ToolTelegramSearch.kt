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

class ToolTelegramSearch(
    private val telegramService: TelegramService,
) : ToolSetup<ToolTelegramSearch.Input> {

    data class Input(
        @InputParamDescription("Text query to search in Telegram messages")
        val query: String,
        @InputParamDescription("Optional chat name for in-chat search")
        val chatName: String? = null,
        @InputParamDescription("Maximum results to return")
        val limit: Int = 25,
    )

    override val name: String = "ToolTelegramSearch"
    override val description: String = "Searches Telegram messages (global or in specific chat) via TDLib search APIs."
    override val fewShotExamples: List<FewShotExample> = listOf(
        FewShotExample(
            request = "Найди в Telegram: когда созвон",
            params = mapOf(
                "query" to "когда созвон",
                "limit" to 20,
            )
        ),
        FewShotExample(
            request = "Найди в чате Проект слово дедлайн",
            params = mapOf(
                "query" to "дедлайн",
                "chatName" to "Проект",
                "limit" to 20,
            )
        ),
    )
    override val returnParameters: ReturnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "JSON with found Telegram messages"),
        )
    )

    override fun invoke(input: Input): String = runBlocking { suspendInvoke(input) }

    override suspend fun suspendInvoke(input: Input): String {
        if (input.query.isBlank()) {
            throw BadInputException("query is required")
        }

        val result = runCatching {
            telegramService.searchMessages(
                query = input.query,
                chatName = input.chatName,
                limit = input.limit,
            )
        }.getOrElse { error ->
            throw BadInputException(error.message ?: "Failed to search Telegram")
        }

        return gigaJsonMapper.writeValueAsString(
            mapOf(
                "count" to result.size,
                "matches" to result.map { msg ->
                    mapOf(
                        "chatId" to msg.chatId,
                        "chatTitle" to msg.chatTitle,
                        "messageId" to msg.messageId,
                        "sender" to msg.sender,
                        "time" to msg.unixTime,
                        "text" to msg.text,
                    )
                }
            )
        )
    }
}
