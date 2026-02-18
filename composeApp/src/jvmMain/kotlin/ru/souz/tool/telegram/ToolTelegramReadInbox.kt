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
