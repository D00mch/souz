package ru.souz.tool.telegram

import kotlinx.coroutines.runBlocking
import ru.souz.llms.restJsonMapper
import ru.souz.service.telegram.TelegramService
import ru.souz.tool.BadInputException
import ru.souz.tool.FewShotExample
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolPermissionBroker
import ru.souz.tool.ToolPermissionResult
import ru.souz.tool.ToolSetup
import souz.composeapp.generated.resources.Res
import souz.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

class ToolTelegramSend(
    private val telegramService: TelegramService,
    private val contactSelectionBroker: TelegramContactSelectionBroker,
    private val permissionBroker: ToolPermissionBroker? = null,
) : ToolSetup<ToolTelegramSend.Input> {

    data class Input(
        @InputParamDescription("Target contact name (fuzzy match in Telegram contact cache)")
        val targetName: String,
        @InputParamDescription("Message text. Can be empty if attachmentPath is provided.")
        val text: String,
        @InputParamDescription("Optional local file path to send as Telegram attachment.")
        val attachmentPath: String? = null,
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
        ),
        FewShotExample(
            request = "Отправь Ане отчет файлом",
            params = mapOf(
                "targetName" to "Аня",
                "text" to "Отправляю отчет",
                "attachmentPath" to "/Users/user/Downloads/report.pdf",
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
        val resolvedInput = TelegramAttachmentPathResolver.resolveInput(
            text = input.text,
            explicitPath = input.attachmentPath,
        )
        val resolvedAttachmentPath = resolvedInput.attachmentPath
        val messageText = resolvedInput.text
        if (messageText.isBlank() && resolvedAttachmentPath == null) {
            throw BadInputException("text is required when attachmentPath is missing")
        }

        val candidate = with(TelegramToolResolvers) {
            contactSelectionBroker.resolveTelegramContactCandidate(
                telegramService = telegramService,
                rawTargetName = input.targetName,
            )
        } ?: return TelegramToolResolvers.telegramSelectionCancelled.msg

        val result = permissionBroker?.requestPermission(
            getString(Res.string.permission_telegram_send),
            linkedMapOf(
                "targetName" to candidate.displayName,
                "targetUsername" to (candidate.username?.let { "@$it" } ?: "-"),
                "text" to messageText,
                "attachmentPath" to (resolvedAttachmentPath ?: "-"),
            )
        )
        if (result is ToolPermissionResult.No) return result.msg

        val sent = runCatching {
            telegramService.sendMessageToUser(
                userId = candidate.userId,
                text = messageText,
                attachmentPath = resolvedAttachmentPath,
            )
        }.getOrElse { error ->
            throw BadInputException(error.message ?: "Failed to send Telegram message")
        }

        return restJsonMapper.writeValueAsString(
            mapOf(
                "status" to "sent",
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
