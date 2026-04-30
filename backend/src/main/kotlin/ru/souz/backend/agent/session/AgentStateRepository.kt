package ru.souz.backend.agent.session

import java.util.UUID

interface AgentStateRepository {
    suspend fun get(userId: String, chatId: UUID): AgentConversationState?
    suspend fun save(state: AgentConversationState): AgentConversationState
}
