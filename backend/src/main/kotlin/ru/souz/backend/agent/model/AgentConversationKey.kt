package ru.souz.backend.agent.model

import java.util.UUID

/** Stable backend conversation identifier composed from user and conversation ids. */
data class AgentConversationKey(
    val userId: String,
    val conversationId: String,
) {
    companion object {
        internal fun fromChat(userId: String, chatId: UUID): AgentConversationKey =
            AgentConversationKey(
                userId = userId,
                conversationId = chatId.toString(),
            )
    }
}

internal fun AgentConversationKey.chatId(): UUID = UUID.fromString(conversationId)
