package ru.souz.memory

class DesktopConversationMemoryRuntime(
    private val memoryService: MemoryService,
    private val captureService: MemoryCaptureService,
) : ConversationMemoryRuntime {
    override suspend fun buildSystemPrompt(
        baseSystemPrompt: String,
        userMessage: String,
        conversationId: String?,
    ): MemoryPromptAugmentationResult {
        val block = memoryService.retrieveForPrompt(
            scopes = scopes(conversationId),
            query = userMessage,
        )
        if (block.rendered.isBlank()) return MemoryPromptAugmentationResult(baseSystemPrompt, emptyList())
        val augmented = if (baseSystemPrompt.isBlank()) {
            block.rendered
        } else {
            buildString {
                append(baseSystemPrompt.trimEnd())
                append("\n\n")
                append(block.rendered)
            }
        }
        val facts = block.hits.map { hit ->
            MemoryPromptAugmentation.Fact(
                factId = hit.fact.id,
                scope = "${hit.fact.scope.type}:${hit.fact.scope.id}",
                score = hit.score,
            )
        }
        return MemoryPromptAugmentationResult(augmented, facts)
    }

    override suspend fun captureCompletedTurn(input: CompletedTurnMemoryInput) {
        captureService.captureAfterTurn(
            MemoryCaptureInput(
                scopes = scopes(input.conversationId),
                primaryScope = GLOBAL_SCOPE,
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

    private companion object {
        val GLOBAL_SCOPE = MemoryScope(type = "global", id = "global")
    }
}
