package ru.souz.backend.agent.model

import java.util.UUID
import ru.souz.backend.execution.model.AgentExecutionRuntimeConfig

/** Stable backend conversation identifier composed from user and conversation ids. */
data class AgentConversationKey(
    val userId: String,
    val conversationId: String,
)

/** Internal request model for one chat-oriented backend agent turn. */
internal data class BackendConversationTurnRequest(
    val prompt: String,
    val model: String,
    val contextSize: Int,
    val locale: String,
    val timeZone: String,
    val executionId: String? = null,
    val temperature: Float? = null,
    val systemPrompt: String? = null,
    val streamingMessages: Boolean? = null,
)

internal data class BackendAgentTurn(
    val userId: String,
    val chatId: UUID,
    val executionId: UUID,
    val conversationId: String,
    val input: BackendAgentTurnInput,
    val config: AgentExecutionRuntimeConfig,
)

internal sealed interface BackendAgentTurnInput {
    data class UserMessage(val content: String) : BackendAgentTurnInput
    data class OptionAnswer(val optionId: UUID, val payload: String) : BackendAgentTurnInput
}
