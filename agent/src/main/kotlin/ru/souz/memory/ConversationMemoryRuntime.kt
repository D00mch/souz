package ru.souz.memory

data class CompletedTurnMemoryInput(
    val conversationId: String?,
    val userMessageId: String?,
    val assistantMessageId: String?,
    val userMessage: String,
    val assistantMessage: String,
)

data class MemoryPromptAugmentation(
    val facts: List<Fact>
) {
    data class Fact(
        val factId: String,
        val scope: String,
        val score: Float,
    )
}

data class MemoryPromptAugmentationResult(
    val augmentedSystemPrompt: String,
    val facts: List<MemoryPromptAugmentation.Fact> = emptyList(),
)

interface ConversationMemoryRuntime {
    suspend fun buildSystemPrompt(
        baseSystemPrompt: String,
        userMessage: String,
        conversationId: String?,
    ): MemoryPromptAugmentationResult

    suspend fun captureCompletedTurn(input: CompletedTurnMemoryInput)
}

object NoopConversationMemoryRuntime : ConversationMemoryRuntime {
    override suspend fun buildSystemPrompt(
        baseSystemPrompt: String,
        userMessage: String,
        conversationId: String?,
    ): MemoryPromptAugmentationResult = MemoryPromptAugmentationResult(baseSystemPrompt)

    override suspend fun captureCompletedTurn(input: CompletedTurnMemoryInput) = Unit
}
