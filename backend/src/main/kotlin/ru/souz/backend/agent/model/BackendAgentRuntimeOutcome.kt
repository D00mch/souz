package ru.souz.backend.agent.model

import ru.souz.backend.agent.session.AgentConversationSession
import ru.souz.llms.LLMResponse

internal sealed interface BackendAgentRuntimeOutcome {
    val session: AgentConversationSession
    val usage: LLMResponse.Usage

    data class Completed(
        val output: String,
        override val usage: LLMResponse.Usage,
        override val session: AgentConversationSession,
    ) : BackendAgentRuntimeOutcome

    data class WaitingOption(
        override val usage: LLMResponse.Usage,
        override val session: AgentConversationSession,
    ) : BackendAgentRuntimeOutcome
}

internal class BackendAgentRuntimeException(
    cause: Throwable,
    val usage: LLMResponse.Usage,
) : RuntimeException(cause)
