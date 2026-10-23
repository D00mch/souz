package ru.souz.backend.agent.runtime.conversation

import ru.souz.agent.spi.RoutedModel
import ru.souz.backend.agent.session.AgentConversationSession
import ru.souz.llms.LLMResponse

/** Result of one backend agent execution turn plus final usage data. */
internal data class BackendConversationExecution(
    val output: String,
    val usage: LLMResponse.Usage,
    val session: AgentConversationSession,
    val attachments: List<ru.souz.llms.FileContent> = emptyList(),
    /** The routing target the turn ran on, or null when it was not routed. */
    val routedModel: RoutedModel? = null,
)
