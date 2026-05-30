package ru.souz.memory

class DesktopConversationMemoryRuntime(
    private val memoryService: MemoryService,
    private val captureService: MemoryCaptureService,
) : ConversationMemoryRuntime {
    override suspend fun retrieveMemory(
        userMessage: String,
        conversationId: String?,
    ): MemoryPromptAugmentationResult {
        val block = memoryService.retrieveForPrompt(
            scopes = scopes(conversationId),
            query = userMessage,
        )
        if (block.rendered.isBlank()) return MemoryPromptAugmentationResult(renderedBlock = "", emptyList())
        val facts = block.hits.map { hit ->
            MemoryPromptFact(
                factId = hit.fact.id,
                scope = "${hit.fact.scope.type}:${hit.fact.scope.id}",
                score = hit.score,
            )
        }
        return MemoryPromptAugmentationResult(renderedBlock = block.rendered, facts = facts)
    }

    override suspend fun captureCompletedTurn(input: CompletedTurnMemoryInput) {
        captureService.captureAfterTurn(
            MemoryCaptureInput(
                scopes = scopes(input.conversationId),
                primaryScope = primaryScope(input.conversationId),
                userMessage = input.userMessage,
                assistantMessage = input.assistantMessage,
                conversationId = input.conversationId,
                userMessageId = input.userMessageId,
                assistantMessageId = input.assistantMessageId,
            )
        )
    }

    private fun scopes(conversationId: String?): List<MemoryScope> = buildList {
        add(GLOBAL_SCOPE)
        conversationId?.let { add(MemoryScope(type = "chat", id = it)) }
    }

    private fun primaryScope(conversationId: String?): MemoryScope =
        conversationId?.let { MemoryScope(type = "chat", id = it) } ?: GLOBAL_SCOPE

    private companion object {
        val GLOBAL_SCOPE = MemoryScope(type = "global", id = "global")
    }
}
