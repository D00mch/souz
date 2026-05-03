package ru.souz.tool.telegram

import ru.souz.llms.ToolInvocationMeta

import kotlinx.coroutines.runBlocking
import ru.souz.llms.restJsonMapper
import ru.souz.service.telegram.TelegramService
import ru.souz.tool.BadInputException
import ru.souz.tool.FewShotExample
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolSetup

class ToolTelegramGetHistory(
    private val telegramService: TelegramService,
    private val chatSelectionBroker: TelegramChatSelectionBroker,
) : ToolSetup<ToolTelegramGetHistory.Input> {

    data class Input(
        @InputParamDescription("Chat name for fuzzy lookup in Telegram chat cache")
        val chatName: String,
        @InputParamDescription("The number of recent messages to return. Defaults to 100.")
        val limit: Int = 100,
        @InputParamDescription("Set true to force reload the latest messages from Telegram and refresh the local history cache")
        val forceRefresh: Boolean = true,
    )

    override val name: String = "ToolTelegramGetHistory"
    override val description: String = "ets the message history for a selected Telegram chat. Use this when the user explicitly asks to inspect, read, or analyze a Telegram chat."
    override val fewShotExamples: List<FewShotExample> = listOf(
        FewShotExample(
            request = "Проанализируй историю чата Проект Альфа",
            params = mapOf(
                "chatName" to "Проект Альфа",
                "limit" to 100,
                "forceRefresh" to true,
            )
        ),
        FewShotExample(
            request = "Прочитай чат Проект Альфа",
            params = mapOf(
                "chatName" to "Проект Альфа",
                "limit" to 100,
                "forceRefresh" to true,
            )
        ),
        FewShotExample(
            request = "Покажи последние 25 сообщений из чата Проект Альфа",
            params = mapOf(
                "chatName" to "Проект Альфа",
                "limit" to 25,
                "forceRefresh" to true,
            )
        )
    )
    override val returnParameters: ReturnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "JSON with messages suitable for summarization"),
        )
    )

    override fun invoke(input: Input, meta: ToolInvocationMeta): String = runBlocking { suspendInvoke(input, meta) }

    override suspend fun suspendInvoke(input: Input, meta: ToolInvocationMeta): String {
        if (input.chatName.isBlank()) {
            throw BadInputException("chatName is required")
        }

        val chatCandidate = with(TelegramToolResolvers) {
            chatSelectionBroker.resolveTelegramChatCandidate(
                telegramService = telegramService,
                rawChatName = input.chatName,
            )
        } ?: return TelegramToolResolvers.telegramSelectionCancelled.msg

        val history = runCatching {
            telegramService.getHistoryByChatId(
                chatId = chatCandidate.chatId,
                limit = input.limit,
                forceRefresh = input.forceRefresh,
            )
        }.getOrElse { error ->
            throw BadInputException(error.message ?: "Failed to fetch Telegram history")
        }

        val summaryReady = history.map { msg ->
            val sender = msg.sender ?: "Unknown"
            val text = msg.text?.trim().orEmpty().ifBlank { "<non-text message>" }
            "[$sender] $text"
        }

        return restJsonMapper.writeValueAsString(
            mapOf(
                "count" to history.size,
                "chatId" to (history.firstOrNull()?.chatId ?: chatCandidate.chatId),
                "chatTitle" to (history.firstOrNull()?.chatTitle ?: chatCandidate.title),
                "forceRefresh" to input.forceRefresh,
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
