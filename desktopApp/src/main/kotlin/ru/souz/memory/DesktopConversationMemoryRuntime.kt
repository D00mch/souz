package ru.souz.memory

class DesktopConversationMemoryRuntime(
    private val memoryService: MemoryService,
    private val captureService: MemoryCaptureService,
) : ConversationMemoryRuntime {
    override suspend fun buildSystemPrompt(
        baseSystemPrompt: String,
        userMessage: String,
        conversationId: String?,
    ): String {
        val block = memoryService.retrieveForPrompt(
            scopes = scopes(conversationId),
            query = userMessage,
        )
        if (block.rendered.isBlank()) return baseSystemPrompt
        if (baseSystemPrompt.isBlank()) return block.rendered
        return buildString {
            append(baseSystemPrompt.trimEnd())
            append("\n\n")
            append(block.rendered)
        }
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
