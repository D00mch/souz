package ru.souz.backend.agent.session

import java.util.UUID

class AgentStateConflictException(
    val userId: String,
    val chatId: UUID,
    val expectedRowVersion: Long,
) : RuntimeException(
    "Agent state for chat $chatId and user $userId changed before save at rowVersion=$expectedRowVersion."
)

interface AgentStateRepository {
    suspend fun get(userId: String, chatId: UUID): AgentConversationState?
    suspend fun save(state: AgentConversationState): AgentConversationState
}
