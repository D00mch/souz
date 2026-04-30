package ru.souz.backend.agent.session

import java.util.concurrent.ConcurrentHashMap
import ru.souz.agent.AgentId
import ru.souz.backend.agent.model.AgentConversationKey
import ru.souz.llms.LLMRequest

/** Persisted backend conversation snapshot used to resume the next agent turn. */
data class AgentConversationSession(
    val activeAgentId: AgentId,
    val history: List<LLMRequest.Message>,
    val temperature: Float,
    val locale: String,
    val timeZone: String,
)

/** Storage contract for per-conversation backend agent state. */
interface AgentSessionRepository {
    suspend fun load(key: AgentConversationKey): AgentConversationSession?
    suspend fun save(key: AgentConversationKey, session: AgentConversationSession)
}

/** Temporary in-memory implementation until backend conversation storage moves to SQL. */
class InMemoryAgentSessionRepository : AgentSessionRepository {
    private val sessions = ConcurrentHashMap<AgentConversationKey, AgentConversationSession>()

    override suspend fun load(key: AgentConversationKey): AgentConversationSession? = sessions[key]

    override suspend fun save(key: AgentConversationKey, session: AgentConversationSession) {
        sessions[key] = session
    }
}
