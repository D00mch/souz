package ru.souz.memory

data class CompletedTurnMemoryInput(
    val conversationId: String?,
    val userMessageId: String,
    val assistantMessageId: String,
    val userMessage: String,
    val assistantMessage: String,
)

interface ConversationMemoryRuntime {
    suspend fun buildSystemPrompt(
        baseSystemPrompt: String,
        userMessage: String,
        conversationId: String?,
    ): String

    suspend fun captureCompletedTurn(input: CompletedTurnMemoryInput)
}

object NoopConversationMemoryRuntime : ConversationMemoryRuntime {
    override suspend fun buildSystemPrompt(
        baseSystemPrompt: String,
        userMessage: String,
        conversationId: String?,
    ): String = baseSystemPrompt

    override suspend fun captureCompletedTurn(input: CompletedTurnMemoryInput) = Unit
}
