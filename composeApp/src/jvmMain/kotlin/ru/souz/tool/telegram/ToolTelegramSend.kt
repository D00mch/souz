package ru.souz.tool.telegram

import kotlinx.coroutines.runBlocking
import ru.souz.giga.gigaJsonMapper
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
                "text" to input.text,
            )
        )
        if (result is ToolPermissionResult.No) return result.msg

        val sent = runCatching {
            telegramService.sendMessageToUser(candidate.userId, input.text)
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
