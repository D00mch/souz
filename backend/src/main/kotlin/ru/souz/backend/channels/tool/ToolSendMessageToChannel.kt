package ru.souz.backend.channels.tool

import kotlinx.coroutines.runBlocking
import ru.souz.backend.channels.ChannelProviderRegistry
import ru.souz.backend.channels.ChannelSendResult
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.tool.BadInputException
import ru.souz.tool.FewShotExample
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolSetup

class ToolSendMessageToChannel(
    private val registry: ChannelProviderRegistry,
) : ToolSetup<ToolSendMessageToChannel.Input> {
    data class Input(
        @InputParamDescription("Channel type from ListActiveChannels, e.g. telegram.")
        val channelType: String,
        @InputParamDescription("Channel id from ListActiveChannels.")
        val channelId: String,
        @InputParamDescription("Message text to deliver.")
        val text: String,
    )

    data class SuccessOutput(val success: Boolean, val detail: String)
    data class FailureOutput(val success: Boolean, val reason: String)

    override val name: String = "SendMessageToChannel"
    override val description: String =
        "Delivers a message into another of the user's configured communication channels " +
            "(from ListActiveChannels) and reports whether delivery succeeded."

    override val fewShotExamples: List<FewShotExample> = listOf(
        FewShotExample(
            request = "Перешли краткую сводку в телеграм",
            params = mapOf("channelType" to "telegram", "channelId" to "<id из ListActiveChannels>", "text" to "Краткая сводка: ..."),
        )
    )

    override val returnParameters: ReturnParameters = ReturnParameters(
        properties = mapOf(
            "success" to ReturnProperty("boolean", "Whether the message was delivered."),
            "detail" to ReturnProperty("string", "Delivery detail, present when success is true."),
            "reason" to ReturnProperty("string", "Failure reason, present when success is false."),
        )
    )

    override fun invoke(input: Input, meta: ToolInvocationMeta): String = runBlocking { suspendInvoke(input, meta) }

    override suspend fun suspendInvoke(input: Input, meta: ToolInvocationMeta): String {
        val text = input.text.trim()
        if (text.isEmpty()) {
            throw BadInputException("text must not be empty.")
        }
        val channelType = input.channelType.trim()
        val channelId = input.channelId.trim()
        if (channelType.isEmpty() || channelId.isEmpty()) {
            throw BadInputException("channelType and channelId must not be empty.")
        }
        val currentConversationId = meta.conversationId?.takeIf { it.isNotBlank() }
        if (channelId == currentConversationId) {
            return restJsonMapper.writeValueAsString(
                FailureOutput(false, "Cannot forward a message to the current channel.")
            )
        }
        return when (val result = registry.send(meta.userId, channelType, channelId, text)) {
            is ChannelSendResult.Delivered -> restJsonMapper.writeValueAsString(SuccessOutput(true, result.detail))
            is ChannelSendResult.Failed -> restJsonMapper.writeValueAsString(FailureOutput(false, result.reason))
        }
    }
}
