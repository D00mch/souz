package ru.souz.ui.main.usecases

import ru.souz.tool.ToolActionDescriptor
import ru.souz.ui.main.ChatAttachedFile
import ru.souz.ui.main.ChatAgentAction
import ru.souz.ui.main.ChatAgentActionStatus
import ru.souz.ui.main.ChatMessage
import ru.souz.ui.main.FinderPathItem

internal class PendingBotMessageState(
    val requestId: Long,
    isVoice: Boolean,
) {
    private val baseMessage = ChatMessage(
        text = "",
        isUser = false,
        isVoice = isVoice,
    )
    private val actionTracker = RequestToolActionTracker()

    val messageId: String get() = baseMessage.id
    val isVoice: Boolean get() = baseMessage.isVoice

    fun buildMessage(
        text: String = baseMessage.text,
        finderPaths: List<FinderPathItem> = baseMessage.finderPaths,
        attachedFiles: List<ChatAttachedFile> = baseMessage.attachedFiles,
    ): ChatMessage = baseMessage.copy(
        text = text,
        finderPaths = finderPaths,
        attachedFiles = attachedFiles,
        agentActions = actionTracker.snapshot(),
    )

    fun updateMessages(
        messages: List<ChatMessage>,
        fallback: ChatMessage = baseMessage,
        transform: (ChatMessage) -> ChatMessage,
    ): List<ChatMessage> = messages.upsertMessage(
        messageId = messageId,
        fallback = fallback,
        transform = transform,
    )

    fun startAction(actionId: String, descriptor: ToolActionDescriptor): List<ChatAgentAction> =
        actionTracker.start(actionId, descriptor)

    fun finishAction(actionId: String, success: Boolean): List<ChatAgentAction> =
        actionTracker.finish(actionId, success)

    fun snapshotActions(): List<ChatAgentAction> = actionTracker.snapshot()
}

internal fun List<ChatMessage>.upsertMessage(
    messageId: String,
    fallback: ChatMessage,
    transform: (ChatMessage) -> ChatMessage,
): List<ChatMessage> {
    val index = indexOfLast { it.id == messageId }
    return if (index >= 0) {
        mapIndexed { currentIndex, value ->
            if (currentIndex == index) transform(value) else value
        }
    } else {
        this + transform(fallback)
    }
}

private class RequestToolActionTracker {
    private val actions = LinkedHashMap<String, ChatAgentAction>()

    @Synchronized
    fun start(actionId: String, descriptor: ToolActionDescriptor): List<ChatAgentAction> {
        actions[actionId] = ChatAgentAction(
            id = actionId,
            descriptor = descriptor,
            status = ChatAgentActionStatus.IN_PROGRESS,
        )
        return snapshot()
    }

    @Synchronized
    fun finish(actionId: String, success: Boolean): List<ChatAgentAction> {
        val current = actions[actionId] ?: return snapshot()
        actions[actionId] = current.copy(
            status = if (success) ChatAgentActionStatus.COMPLETED else ChatAgentActionStatus.FAILED
        )
        return snapshot()
    }

    @Synchronized
    fun snapshot(): List<ChatAgentAction> = actions.values.toList()
}
