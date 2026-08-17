package ru.souz.backend.channels.tool

import kotlinx.coroutines.runBlocking
import ru.souz.backend.channels.ChannelProviderRegistry
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.tool.FewShotExample
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolSetup

class ToolListActiveChannels(
    private val registry: ChannelProviderRegistry,
) : ToolSetup<ToolListActiveChannels.Input> {
    class Input

    data class ChannelJson(val channelType: String, val channelId: String, val label: String)
    data class Output(val channels: List<ChannelJson>)

    override val name: String = "ListActiveChannels"
    override val description: String =
        "Lists the calling user's other configured communication channels (e.g. Telegram) " +
            "that a message from the current conversation can be forwarded to. Returns an empty list " +
            "if the user has no other configured channels."

    override val fewShotExamples: List<FewShotExample> = listOf(
        FewShotExample(request = "Куда я могу переслать сообщение?", params = emptyMap()),
    )

    override val returnParameters: ReturnParameters = ReturnParameters(
        properties = mapOf(
            "channels" to ReturnProperty(
                "array",
                "List of {channelType, channelId, label} the user can forward a message to.",
            ),
        )
    )

    override fun invoke(input: Input, meta: ToolInvocationMeta): String = runBlocking { suspendInvoke(input, meta) }

    override suspend fun suspendInvoke(input: Input, meta: ToolInvocationMeta): String {
        val channels = registry.listAll(meta.userId, excludeChannelId = meta.conversationId?.takeIf { it.isNotBlank() })
            .map { ChannelJson(it.channelType, it.channelId, it.label) }
        return restJsonMapper.writeValueAsString(Output(channels))
    }
}
