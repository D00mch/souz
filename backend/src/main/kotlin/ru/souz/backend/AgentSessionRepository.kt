package ru.souz.backend

import java.util.concurrent.ConcurrentHashMap
import ru.souz.agent.AgentId
import ru.souz.agent.state.AgentContext

data class AgentConversationSession(
    val activeAgentId: AgentId,
    val context: AgentContext<String>,
    val locale: String,
    val timeZone: String,
)

interface AgentSessionRepository {
    suspend fun load(key: AgentConversationKey): AgentConversationSession?
    suspend fun save(key: AgentConversationKey, session: AgentConversationSession)
}

class InMemoryAgentSessionRepository : AgentSessionRepository {
    private val sessions = ConcurrentHashMap<AgentConversationKey, AgentConversationSession>()

    override suspend fun load(key: AgentConversationKey): AgentConversationSession? = sessions[key]

    override suspend fun save(key: AgentConversationKey, session: AgentConversationSession) {
        sessions[key] = session
    }
}
