package ru.souz.backend.agent.session

import ru.souz.backend.testutil.repository.MemoryAgentStateRepository

/** Test-only in-memory session repository. */
class InMemoryAgentSessionRepository(
    stateRepository: AgentStateRepository = MemoryAgentStateRepository(),
) : AgentSessionRepository by AgentStateBackedSessionRepository(stateRepository)
