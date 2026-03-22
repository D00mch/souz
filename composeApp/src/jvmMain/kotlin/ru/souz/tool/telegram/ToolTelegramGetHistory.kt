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

class ToolTelegramGetHistory(
    private val telegramService: TelegramService,
    private val chatSelectionBroker: TelegramChatSelectionBroker,
) : ToolSetup<ToolTelegramGetHistory.Input> {

    data class Input(
        @InputParamDescription("Chat name for fuzzy lookup in Telegram chat cache")
        val chatName: String,
        @InputParamDescription("How many recent messages to return. If the user asked to inspect/read/analyze a chat but didn't name a count, keep the default 100.")
        val limit: Int = 100,
        @InputParamDescription("Set true to force reload the latest messages from Telegram and refresh the local history cache")
        val forceRefresh: Boolean = true,
    )

    override val name: String = "ToolTelegramGetHistory"
    override val description: String = "Gets Telegram chat history for a selected chat. Use this when the user explicitly asks to inspect/read/analyze a Telegram chat. If they don't specify how many messages to read, it force-loads and caches the latest 100 messages by default."
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

    override fun invoke(input: Input): String = runBlocking { suspendInvoke(input) }

    override suspend fun suspendInvoke(input: Input): String {
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

        return gigaJsonMapper.writeValueAsString(
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
