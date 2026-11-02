package ru.souz.backend.attachments

import kotlinx.coroutines.CancellationException
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.llms.FileContent
import ru.souz.llms.FilePresentation
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.tool.ToolCategory

/** Explicit file selection tools, with no channel delivery side effects. */
internal fun attachmentToolCatalog(access: BackendAttachmentAccess): AgentToolCatalog =
    AgentToolCatalog(mapOf(ToolCategory.FILES to listOf(
        ToolPrepareAttachment(access),
        ToolAttachFileToReply(access),
        ToolDetachFileFromReply(access),
    )))

internal class ToolPrepareAttachment(private val access: BackendAttachmentAccess) : LLMToolSetup {
    data class Input(val fileId: String? = null)

    override val fn = LLMRequest.Function(
        name = NAME,
        description = "Resolve a received fileId to a working copy in your sandbox for reading or processing.",
        parameters = LLMRequest.Parameters("object", mapOf("fileId" to FILE_ID_PROPERTY), required = listOf("fileId")),
    )

    override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
        invoke(functionCall, ToolInvocationMeta.localDefault())

    override suspend fun invoke(functionCall: LLMResponse.FunctionCall, meta: ToolInvocationMeta): LLMRequest.Message =
        attachmentToolResult(NAME) {
            val fileId = functionCall.input<Input>().fileId ?: throw AttachmentException("attachment_file_id_required")
            val prepared = access.prepare(meta, fileId)
            toolMessage(NAME, prepared, files = listOf(prepared.attachment))
        }

    companion object {
        const val NAME = "PrepareAttachment"
    }
}

internal class ToolAttachFileToReply(private val access: BackendAttachmentAccess) : LLMToolSetup {
    data class Input(val path: String? = null, val fileId: String? = null, val presentation: String? = null)

    override val fn = LLMRequest.Function(
        name = NAME,
        description = "Select a completed file for your reply. Supply exactly one of sandbox path or fileId. Does not send a message.",
        parameters = LLMRequest.Parameters("object", mapOf(
            "fileId" to FILE_ID_PROPERTY,
            "path" to LLMRequest.Property("string", "Completed sandbox file to attach."),
            "presentation" to LLMRequest.Property("string", "How to show the file; defaults to document.",
                enum = FilePresentation.entries.map { it.name }),
        )),
    )

    override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
        invoke(functionCall, ToolInvocationMeta.localDefault())

    override suspend fun invoke(functionCall: LLMResponse.FunctionCall, meta: ToolInvocationMeta): LLMRequest.Message =
        attachmentToolResult(NAME) {
            val input = functionCall.input<Input>()
            val presentation = input.presentation?.let { raw -> FilePresentation.entries.firstOrNull { it.name == raw } }
                ?: if (input.presentation == null) FilePresentation.document else throw AttachmentException("attachment_presentation_invalid")
            val file = when {
                input.path != null && input.fileId == null -> access.save(meta, input.path, presentation)
                input.fileId != null && input.path == null -> access.select(meta, input.fileId, presentation)
                else -> throw AttachmentException("attachment_source_required")
            }
            toolMessage(NAME, file, files = listOf(file), replyFiles = listOf(file))
        }

    companion object {
        const val NAME = "AttachFileToReply"
    }
}

internal class ToolDetachFileFromReply(private val access: BackendAttachmentAccess) : LLMToolSetup {
    data class Input(val fileId: String? = null)

    override val fn = LLMRequest.Function(
        name = NAME,
        description = "Remove a fileId from this reply's selection. Keeps the file in storage; use before attaching a corrected version.",
        parameters = LLMRequest.Parameters("object", mapOf("fileId" to FILE_ID_PROPERTY), required = listOf("fileId")),
    )

    override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
        invoke(functionCall, ToolInvocationMeta.localDefault())

    override suspend fun invoke(functionCall: LLMResponse.FunctionCall, meta: ToolInvocationMeta): LLMRequest.Message =
        attachmentToolResult(NAME) {
            val fileId = functionCall.input<Input>().fileId?.takeIf { it.isNotBlank() }
                ?: throw AttachmentException("attachment_file_id_required")
            access.requireSupportedSandbox(meta)
            toolMessage(NAME, mapOf("fileId" to fileId, "selected" to false),
                detachedReplyFiles = listOf(fileId))
        }

    companion object {
        const val NAME = "DetachFileFromReply"
    }
}

private val FILE_ID_PROPERTY = LLMRequest.Property("string", "Stable attachment ID from the conversation or a file tool.")

private inline fun <reified T> LLMResponse.FunctionCall.input(): T = try {
    restJsonMapper.convertValue(arguments, T::class.java)
} catch (_: IllegalArgumentException) {
    throw AttachmentException("attachment_invalid_arguments")
}

private fun toolMessage(
    name: String,
    result: Any,
    files: List<FileContent> = emptyList(),
    replyFiles: List<FileContent> = emptyList(),
    detachedReplyFiles: List<String> = emptyList(),
) = LLMRequest.Message(LLMMessageRole.function, restJsonMapper.writeValueAsString(result), name = name,
    files = files, replyFiles = replyFiles, detachedReplyFiles = detachedReplyFiles)

// Only safe codes reach the model; credentials, signed URLs and exception messages are never tool output.
private suspend fun attachmentToolResult(name: String, block: suspend () -> LLMRequest.Message): LLMRequest.Message = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: AttachmentException) {
    toolMessage(name, mapOf("error" to error.code))
}
