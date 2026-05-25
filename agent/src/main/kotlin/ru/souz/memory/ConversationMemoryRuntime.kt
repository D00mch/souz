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
    val renderedBlock: String,
    val facts: List<MemoryPromptAugmentation.Fact> = emptyList(),
)

interface ConversationMemoryRuntime {
    suspend fun retrieveMemory(
        userMessage: String,
        conversationId: String?,
    ): MemoryPromptAugmentationResult

    suspend fun captureCompletedTurn(input: CompletedTurnMemoryInput)
}

object NoopConversationMemoryRuntime : ConversationMemoryRuntime {
    override suspend fun retrieveMemory(
        userMessage: String,
        conversationId: String?,
    ): MemoryPromptAugmentationResult = MemoryPromptAugmentationResult(renderedBlock = "")

    override suspend fun captureCompletedTurn(input: CompletedTurnMemoryInput) = Unit
}
