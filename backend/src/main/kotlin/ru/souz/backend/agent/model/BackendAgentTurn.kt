package ru.souz.backend.agent.model

import java.util.UUID
import ru.souz.backend.execution.model.AgentExecutionRuntimeConfig

internal data class BackendAgentTurn(
    val userId: String,
    val chatId: UUID,
    val executionId: UUID,
    val conversationId: String,
    val input: BackendAgentTurnInput,
    val config: AgentExecutionRuntimeConfig,
) {
    val conversationKey: AgentConversationKey
        get() = AgentConversationKey(
            userId = userId,
            conversationId = conversationId,
        )
}

internal sealed interface BackendAgentTurnInput {
    data class UserMessage(
        val content: String,
    ) : BackendAgentTurnInput

    data class OptionAnswer(
        val optionId: UUID,
        val payload: String,
    ) : BackendAgentTurnInput
}
