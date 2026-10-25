package ru.souz.tool.files

import kotlinx.coroutines.CancellationException
import ru.souz.agent.spi.AgentAttachmentAccess
import ru.souz.llms.FilePresentation
import ru.souz.llms.FileReference
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper

/** Explicit file selection, with no channel delivery side effects. */
class AttachmentTools(private val access: AgentAttachmentAccess) {
    val tools: List<LLMToolSetup> = listOf("PrepareAttachment", "AttachFileToReply", "DetachFileFromReply").map(::tool)
    val catalog = ru.souz.agent.spi.AgentToolCatalog(mapOf(ru.souz.tool.ToolCategory.FILES to tools))

    private fun tool(name: String): LLMToolSetup = object : LLMToolSetup {
        override val fn = LLMRequest.Function(
            name = name,
            description = when (name) {
                "AttachFileToReply" -> "Select a completed file for your reply. Supply exactly one of sandbox path or fileId. Does not send a message."
                "DetachFileFromReply" -> "Remove a fileId from this reply's selection. Keeps the file in storage; use before attaching a corrected version."
                else -> "Resolve a received fileId to a working copy in your sandbox for reading or processing."
            },
            parameters = LLMRequest.Parameters("object", buildMap {
                put("fileId", LLMRequest.Property("string", "Stable attachment ID from the conversation or a file tool."))
                if (name == "AttachFileToReply") {
                    put("path", LLMRequest.Property("string", "Completed sandbox file to attach."))
                    put("presentation", LLMRequest.Property("string", "How to show the file; defaults to document.",
                        enum = FilePresentation.entries.map { it.name }))
                }
            }, required = if (name == "AttachFileToReply") emptyList() else listOf("fileId")),
        )

        override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
            invoke(functionCall, ToolInvocationMeta.localDefault())

        override suspend fun invoke(functionCall: LLMResponse.FunctionCall, meta: ToolInvocationMeta): LLMRequest.Message {
            return try {
                val args = functionCall.arguments
                val id = args["fileId"] as? String
                if (name == "AttachFileToReply") {
                    val path = args["path"] as? String
                    require((path != null) xor (id != null)) { "Supply exactly one of path or fileId." }
                    val presentation = (args["presentation"] as? String)?.let(FilePresentation::valueOf) ?: FilePresentation.document
                    val file = access.register(meta, path, id, presentation)
                    LLMRequest.Message(LLMMessageRole.function, restJsonMapper.writeValueAsString(file), name = fn.name,
                        files = listOf(file), replyFiles = listOf(file))
                } else if (name == "DetachFileFromReply") {
                    require(!id.isNullOrBlank()) { "fileId is required." }
                    LLMRequest.Message(LLMMessageRole.function,
                        restJsonMapper.writeValueAsString(mapOf("fileId" to id, "selected" to false)), name = fn.name,
                        detachedReplyFiles = listOf(FileReference(id)))
                } else {
                    val prepared = access.prepare(meta, requireNotNull(id) { "fileId is required." })
                    LLMRequest.Message(LLMMessageRole.function, restJsonMapper.writeValueAsString(prepared), name = fn.name,
                        files = listOf(prepared.attachment))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Host must expose safe errors only; credentials and signed URLs are not tool output.
                LLMRequest.Message(LLMMessageRole.function, restJsonMapper.writeValueAsString(mapOf(
                    "error" to (error.message ?: "attachment_operation_failed"),
                )), name = fn.name)
            }
        }
    }
}
