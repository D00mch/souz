package ru.souz.backend.execution.model

import java.time.Instant
import java.util.UUID
import ru.souz.llms.LLMModel
import ru.souz.llms.LlmProvider

data class AgentExecutionUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val precachedTokens: Int,
)

data class AgentExecution(
    val id: UUID,
    val userId: String,
    val chatId: UUID,
    val userMessageId: UUID?,
    val assistantMessageId: UUID?,
    val status: AgentExecutionStatus,
    val requestId: String?,
    val clientMessageId: String?,
    val model: LLMModel?,
    val provider: LlmProvider?,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val cancelRequested: Boolean,
    val errorCode: String?,
    val errorMessage: String?,
    val usage: AgentExecutionUsage?,
    val metadata: Map<String, String>,
)
