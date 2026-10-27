package ru.souz.llms

/** Per-message file limits shared by reply selection and the host's commit validation. */
const val MAX_MESSAGE_FILES: Int = 10
const val MAX_MESSAGE_FILE_BYTES: Long = 100_000_000L

data class ReplyFileSelection(val files: List<FileContent>, val messages: List<LLMRequest.Message>)

/**
 * Apply trusted tool results in call order; a later selection replaces its metadata. A result whose changes would
 * exceed the message limits is rejected as a whole and becomes a tool error, so the model can adapt before commit.
 */
fun List<FileContent>.applyReplyFileSelection(messages: List<LLMRequest.Message>): ReplyFileSelection {
    var selected = this
    val results = messages.map { message ->
        if (message.replyFiles.isEmpty() && message.detachedReplyFiles.isEmpty()) return@map message
        val next = selected.associateByTo(linkedMapOf()) { it.file }.apply {
            message.detachedReplyFiles.forEach(::remove)
            message.replyFiles.forEach { this[it.file] = it }
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
