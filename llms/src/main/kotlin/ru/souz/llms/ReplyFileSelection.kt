package ru.souz.llms

/** Apply trusted tool results in call order; a later selection replaces its metadata. */
fun List<FileContent>.applyReplyFileSelection(messages: List<LLMRequest.Message>): List<FileContent> {
    val selected = associateByTo(linkedMapOf()) { it.file }
    for (message in messages) {
        message.detachedReplyFiles.forEach(selected::remove)
        message.replyFiles.forEach { selected[it.file] = it }
    }
    return selected.values.toList()
}
