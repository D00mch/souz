package ru.souz.memory

/**
 * Completed conversation turn passed to memory capture after the assistant responds.
 */
data class CompletedTurnMemoryInput(
    val conversationId: String?,
    val userMessageId: String?,
    val assistantMessageId: String?,
    val userMessage: String,
    val assistantMessage: String,
)

/**
 * Single fact reference included into the prompt augmentation.
 */
data class MemoryPromptFact(
    val factId: String,
    val scope: String,
    val score: Float,
)

/**
 * Rendered memory block plus referenced fact metadata for tracing and UI.
 */
data class MemoryPromptAugmentationResult(
    val renderedBlock: String,
    val facts: List<MemoryPromptFact> = emptyList(),
)

/**
 * Conversation-scoped entry point for prompt memory retrieval and post-turn capture.
 */
interface ConversationMemoryRuntime {
    suspend fun retrieveMemory(
        userMessage: String,
        conversationId: String?,
    ): MemoryPromptAugmentationResult

    suspend fun captureCompletedTurn(input: CompletedTurnMemoryInput)
}

/**
 * No-op runtime used when memory integration is disabled.
 */
object NoopConversationMemoryRuntime : ConversationMemoryRuntime {
    override suspend fun retrieveMemory(
        userMessage: String,
        conversationId: String?,
    ): MemoryPromptAugmentationResult = MemoryPromptAugmentationResult(renderedBlock = "")

    override suspend fun captureCompletedTurn(input: CompletedTurnMemoryInput) = Unit
}
