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

class ToolTelegramReadInbox(
    private val telegramService: TelegramService,
) : ToolSetup<ToolTelegramReadInbox.Input> {

    data class Input(
        @InputParamDescription("Maximum unread chats to return")
        val limit: Int = 20,
    )

    override val name: String = "ToolTelegramReadInbox"
    override val description: String = "Returns unread Telegram chats from cache as: Chat: [Title], Unread: [N], Last: [Text]."
    override val fewShotExamples: List<FewShotExample> = listOf(
        FewShotExample(
            request = "Проверь непрочитанные чаты в Telegram",
            params = mapOf("limit" to 20),
        )
    )
    override val returnParameters: ReturnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "JSON with unread chat lines"),
        )
    )

    override fun invoke(input: Input): String = runBlocking { suspendInvoke(input) }

    override suspend fun suspendInvoke(input: Input): String {
        val limit = input.limit.coerceIn(1, 100)
        val items = runCatching {
            telegramService.readUnreadInbox(limit)
        }.getOrElse { error ->
            throw BadInputException(error.message ?: "Failed to read Telegram inbox")
        }

        val lines = items.map { item ->
            val lastText = item.lastText?.trim().orEmpty().ifBlank { "<empty>" }
            "Chat: ${item.title}, Unread: ${item.unreadCount}, Last: $lastText"
        }

        return gigaJsonMapper.writeValueAsString(
            mapOf(
                "count" to lines.size,
                "items" to lines,
            )
        )
    }
}
