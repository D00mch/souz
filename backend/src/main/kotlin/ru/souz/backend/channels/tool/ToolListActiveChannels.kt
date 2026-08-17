package ru.souz.backend.channels.tool

import kotlinx.coroutines.runBlocking
import ru.souz.backend.channels.ChannelDescriptor
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

    data class Output(val channels: List<ChannelDescriptor>)

    override val name: String = "ListActiveChannels"
    override val description: String =
        "Lists the calling user's configured communication channels (e.g. Telegram) " +
            "that a message can be forwarded to. Returns an empty list " +
            "if the user has no configured channels."

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
        return restJsonMapper.writeValueAsString(Output(registry.listAll(meta.userId)))
    }
}
