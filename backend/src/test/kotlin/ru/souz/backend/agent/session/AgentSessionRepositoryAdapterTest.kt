package ru.souz.backend.agent.session

import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import ru.souz.agent.AgentId
import ru.souz.backend.agent.model.AgentConversationKey
import ru.souz.backend.storage.memory.MemoryAgentStateRepository
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest

class AgentSessionRepositoryAdapterTest {
    @Test
    fun `adapter preserves legacy session fields through agent state repository`() = runTest {
        val stateRepository = MemoryAgentStateRepository()
        val repository = AgentStateBackedSessionRepository(stateRepository)
        val key = AgentConversationKey(userId = "user-a", conversationId = UUID.randomUUID().toString())
        val session = AgentConversationSession(
            activeAgentId = AgentId.LUA_GRAPH,
            history = listOf(
                LLMRequest.Message(
                    role = LLMMessageRole.user,
                    content = "hello",
                ),
                LLMRequest.Message(
                    role = LLMMessageRole.assistant,
                    content = "world",
                ),
            ),
            temperature = 0.25f,
            locale = "en-US",
            timeZone = "Europe/Amsterdam",
        )

        repository.save(key, session)

        assertEquals(session, repository.load(key))
        val storedState = stateRepository.get("user-a", UUID.fromString(key.conversationId))
        assertEquals(AgentId.LUA_GRAPH, storedState?.activeAgentId)
        assertEquals(0.25f, storedState?.temperature)
        assertEquals(Locale.forLanguageTag("en-US"), storedState?.locale)
        assertEquals(ZoneId.of("Europe/Amsterdam"), storedState?.timeZone)
        assertEquals(session.history, storedState?.history)
    }
}
