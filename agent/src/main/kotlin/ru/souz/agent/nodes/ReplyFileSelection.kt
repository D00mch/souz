package ru.souz.agent.nodes

import ru.souz.llms.FileContent
import ru.souz.llms.LLMRequest
import ru.souz.llms.MAX_MESSAGE_FILES
import ru.souz.llms.MAX_MESSAGE_FILE_BYTES
import ru.souz.llms.restJsonMapper

internal data class ReplyFileSelection(val files: List<FileContent>, val messages: List<LLMRequest.Message>)

/**
 * Apply trusted tool results in call order; a later selection replaces its metadata. A result whose changes would
 * exceed the message limits is rejected as a whole and becomes a tool error, so the model can adapt before commit.
 */
internal fun List<FileContent>.applyReplyFileSelection(messages: List<LLMRequest.Message>): ReplyFileSelection {
    var selected = this
    val results = messages.map { message ->
        if (message.replyFiles.isEmpty() && message.detachedReplyFiles.isEmpty()) return@map message
        val next = selected.associateByTo(linkedMapOf()) { it.fileId }.apply {
            message.detachedReplyFiles.forEach(::remove)
            message.replyFiles.forEach { this[it.fileId] = it }
        }.values.toList()
        if (next.size <= MAX_MESSAGE_FILES && next.sumOf { it.sizeBytes } <= MAX_MESSAGE_FILE_BYTES) {
            selected = next
            message
        } else {
            message.copy(
                content = restJsonMapper.writeValueAsString(linkedMapOf(
                    "error" to "attachment_reply_limit_exceeded",
                    "maxFiles" to MAX_MESSAGE_FILES,
                    "maxTotalBytes" to MAX_MESSAGE_FILE_BYTES,
                    "selectedFiles" to selected.size,
                    "selectedBytes" to selected.sumOf { it.sizeBytes },
                    "instruction" to "The reply selection is unchanged. Detach files or attach fewer or smaller files.",
                    "result" to message.content,
                )),
                replyFiles = emptyList(),
                detachedReplyFiles = emptyList(),
            )
        }
    }
    return ReplyFileSelection(selected, results)
}
