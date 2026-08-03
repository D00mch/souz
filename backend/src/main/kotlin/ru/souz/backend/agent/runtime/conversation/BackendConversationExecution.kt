package ru.souz.backend.agent.runtime.conversation

import ru.souz.backend.agent.session.AgentConversationSession
import ru.souz.llms.LLMResponse

/** Result of one backend agent execution turn plus final usage data. */
internal data class BackendConversationExecution(
    val output: String,
    val usage: LLMResponse.Usage,
    val session: AgentConversationSession,
)
