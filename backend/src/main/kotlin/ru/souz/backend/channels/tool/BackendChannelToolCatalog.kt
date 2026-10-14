package ru.souz.backend.channels.tool

import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.llms.giga.toGiga
import ru.souz.tool.ToolCategory

class BackendChannelToolCatalog(
    toolListActiveChannels: ToolListActiveChannels,
    toolSendMessageToChannel: ToolSendMessageToChannel,
) {
    val catalog = AgentToolCatalog(
        mapOf(
            ToolCategory.CHANNEL_MESSAGING to listOf(
                toolListActiveChannels.toGiga(),
                toolSendMessageToChannel.toGiga(),
            ),
        )
    )
}
