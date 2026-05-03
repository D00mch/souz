package ru.souz.backend.storage.memory

import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.souz.backend.agent.session.AgentConversationState
import ru.souz.backend.agent.session.AgentStateRepository

class MemoryAgentStateRepository(
    maxEntries: Int,
) : AgentStateRepository {
    private val mutex = Mutex()
    private val states = boundedLruMap<StateKey, AgentConversationState>(maxEntries)

    constructor() : this(DEFAULT_MEMORY_REPOSITORY_MAX_ENTRIES)

    override suspend fun get(userId: String, chatId: UUID): AgentConversationState? = mutex.withLock {
        states[StateKey(userId, chatId)]
    }

    override suspend fun save(state: AgentConversationState): AgentConversationState = mutex.withLock {
        states[StateKey(state.userId, state.chatId)] = state
        state
    }
}

private data class StateKey(
    val userId: String,
    val chatId: UUID,
)
