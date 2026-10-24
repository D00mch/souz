package ru.souz.agent.spi

import ru.souz.agent.MessageAttachment
import ru.souz.llms.FilePresentation
import ru.souz.llms.ToolInvocationMeta

data class PreparedAttachment(val attachment: MessageAttachment, val path: String)

/** Host owns authorization, immutable storage and sandbox resolution. */
interface AgentAttachmentAccess {
    suspend fun prepare(meta: ToolInvocationMeta, fileId: String): PreparedAttachment
    suspend fun register(meta: ToolInvocationMeta, path: String?, fileId: String?, presentation: FilePresentation): MessageAttachment
}
