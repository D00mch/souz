package ru.souz.tool.telegram

import kotlinx.coroutines.runBlocking
import ru.souz.giga.gigaJsonMapper
import ru.souz.service.telegram.TelegramService
import ru.souz.tool.BadInputException
import ru.souz.tool.FewShotExample
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolActionDescriptor
import ru.souz.tool.ToolActionKind
import ru.souz.tool.ToolSetup

class ToolTelegramSavedMessages(
    private val telegramService: TelegramService,
) : ToolSetup<ToolTelegramSavedMessages.Input> {

    data class Input(
        @InputParamDescription("Text to save into Telegram Saved Messages. Can be empty if attachmentPath is provided.")
        val text: String,
        @InputParamDescription("Optional local file path to send as attachment to Telegram Saved Messages.")
        val attachmentPath: String? = null,
    )

    override val name: String = "ToolTelegramSavedMessages"
    override val description: String = "Saves text to Telegram Saved Messages (chat with self)."
    override val fewShotExamples: List<FewShotExample> = listOf(
        FewShotExample(
            request = "Сохрани в Избранное Telegram: созвон в 16:30",
            params = mapOf("text" to "Созвон в 16:30"),
        ),
        FewShotExample(
            request = "Отправь в Избранное файл отчета",
            params = mapOf(
                "text" to "Отчет",
                "attachmentPath" to "/Users/user/Downloads/report.pdf",
            ),
        )
    )
    override val returnParameters: ReturnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "JSON with sent message details"),
        )
    )

    override fun describeAction(input: Input): ToolActionDescriptor? = ToolActionDescriptor(
        kind = ToolActionKind.SAVE_TELEGRAM_SAVED_MESSAGES,
    )

    override fun invoke(input: Input): String = runBlocking { suspendInvoke(input) }

    override suspend fun suspendInvoke(input: Input): String {
        val resolvedInput = TelegramAttachmentPathResolver.resolveInput(
            text = input.text,
            explicitPath = input.attachmentPath,
        )
        val resolvedAttachmentPath = resolvedInput.attachmentPath
        val messageText = resolvedInput.text
        if (messageText.isBlank() && resolvedAttachmentPath == null) {
            throw BadInputException("text is required when attachmentPath is missing")
        }

        val sent = runCatching {
            telegramService.sendToSavedMessages(
                text = messageText,
                attachmentPath = resolvedAttachmentPath,
            )
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
                "attachmentPath" to resolvedAttachmentPath,
                "time" to sent.unixTime,
            )
        )
    }
}
