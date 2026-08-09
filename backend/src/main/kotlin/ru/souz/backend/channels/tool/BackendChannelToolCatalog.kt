package ru.souz.backend.channels.tool

import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.giga.toGiga
import ru.souz.tool.ToolCategory

class BackendChannelToolCatalog(
    toolListActiveChannels: ToolListActiveChannels,
    toolSendMessageToChannel: ToolSendMessageToChannel,
) : AgentToolCatalog {
    override val toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>> = mapOf(
        ToolCategory.CHANNEL_MESSAGING to listOf(
            toolListActiveChannels.toGiga(),
            toolSendMessageToChannel.toGiga(),
        ).associateBy { it.fn.name }
    )
}
