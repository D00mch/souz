package ru.souz.backend.agent.model

import ru.souz.llms.LLMModel

/** Stable backend conversation identifier composed from user and conversation ids. */
data class AgentConversationKey(
    val userId: String,
    val conversationId: String,
)

/** Internal request model for one chat-oriented backend agent turn. */
internal data class BackendConversationTurnRequest(
    val prompt: String,
    val model: LLMModel,
    val contextSize: Int,
    val locale: String,
    val timeZone: String,
    val executionId: String? = null,
    val inputMessageSeq: Long? = null,
    val temperature: Float? = null,
    val systemPrompt: String? = null,
    val streamingMessages: Boolean? = null,
    val requestTimeoutMillis: Long? = null,
    val useFewShotExamples: Boolean? = null,
    val enabledTools: Set<String>? = null,
    val clientToolsEnabled: Boolean = false,
)
