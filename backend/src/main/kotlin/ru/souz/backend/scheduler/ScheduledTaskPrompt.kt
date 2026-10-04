package ru.souz.backend.scheduler

import ru.souz.backend.channels.ChannelDescriptor

private const val DELIVERY_START = "<scheduled-task-delivery>"
private const val DELIVERY_END = "</scheduled-task-delivery>"

internal fun scheduledTaskPrompt(prompt: String, source: ChannelDescriptor?): String =
    checkedPrompt("$prompt\n\n$DELIVERY_START\n${deliveryInstruction(source)}\n$DELIVERY_END")

/** Preserve the original destination when a task is edited from another conversation. */
internal fun updatedScheduledTaskPrompt(prompt: String, previous: String): String {
    val previousBlock = deliveryBlock(previous)
    val incomingBlock = deliveryBlock(prompt)
    val instruction = if (incomingBlock == null) prompt else prompt.removeRange(incomingBlock).trimEnd()
    val context = previousBlock?.let { previous.substring(it) }
        ?: "$DELIVERY_START\n${deliveryInstruction(null)}\n$DELIVERY_END"
    return checkedPrompt("$instruction\n\n$context")
}

private fun checkedPrompt(prompt: String): String = prompt.also { taskCheck(it.length <= 16_384) }

private fun deliveryBlock(prompt: String): IntRange? {
    val start = prompt.lastIndexOf(DELIVERY_START)
    if (start < 0) return null
    val end = prompt.indexOf(DELIVERY_END, start)
    return if (end < 0) null else start until end + DELIVERY_END.length
}

private fun deliveryInstruction(source: ChannelDescriptor?): String = buildString {
    appendLine("This scheduled task runs in a fresh hidden technical chat. Its final assistant answer is not delivered to the user.")
    if (source != null) {
        appendLine("Default reply destination from the original request: channelType=${source.channelType}, channelId=${source.channelId}.")
        appendLine("If the task calls for a message to the user and does not explicitly specify a different destination, send it to this default destination using SendMessageToChannel.")
    } else {
        appendLine("No user-facing source channel is available. Use the destination explicitly provided in the task instruction and SendMessageToChannel when a message to the user is required; never use this technical chat as the destination.")
    }
    appendLine("An explicit delivery destination in the task instruction takes precedence. Do not send a notification when the task asks for silent execution or only an action without a user message.")
    appendLine("Check the tool's delivery result; a final answer alone does not mean the user was notified.")
    append("When creating a child scheduled task, include the required delivery destination in its prompt, including this default when applicable. When updating a task, put explicit destination changes in the task instruction; the original default is preserved automatically.")
}
