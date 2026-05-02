package ru.souz.backend.agent.model

/** Stable backend conversation identifier composed from user and conversation ids. */
data class AgentConversationKey(
    val userId: String,
    val conversationId: String,
)

/** Internal request model for one chat-oriented backend agent turn. */
internal data class BackendConversationTurnRequest(
    val prompt: String,
    val model: String,
    val contextSize: Int,
    val locale: String,
    val timeZone: String,
    val executionId: String? = null,
    val temperature: Float? = null,
    val systemPrompt: String? = null,
    val streamingMessages: Boolean? = null,
)
