package ru.souz.memory

import ru.souz.db.ConfigStore
import java.util.UUID

interface DesktopMemoryProjectContextProvider {
    fun currentProjectId(): ProjectId?
}

object NoopDesktopMemoryProjectContextProvider : DesktopMemoryProjectContextProvider {
    override fun currentProjectId(): ProjectId? = null
}

class DesktopMemoryOwnerProvider : MemoryOwnerProvider {
    override fun currentOwnerId(): MemoryOwnerId {
        ConfigStore.get<String>(OWNER_KEY)?.trim()?.takeIf(String::isNotBlank)?.let { return MemoryOwnerId(it) }
        val generated = UUID.randomUUID().toString()
        ConfigStore.put(OWNER_KEY, generated)
        return MemoryOwnerId(generated)
    }

    private companion object {
        private const val OWNER_KEY = "MEMORY_LOCAL_OWNER_ID"
    }
}

class DesktopMemoryContextProvider(
    private val projectContextProvider: DesktopMemoryProjectContextProvider = NoopDesktopMemoryProjectContextProvider,
    private val ownerProvider: MemoryOwnerProvider = DesktopMemoryOwnerProvider(),
) {
    fun current(conversationId: String?): MemoryContext = MemoryContext(
        ownerId = ownerProvider.currentOwnerId(),
        conversationId = conversationId?.let(::ConversationId),
        sessionId = conversationId?.let(::MemorySessionId),
        projectId = projectContextProvider.currentProjectId(),
    )
}

class DesktopConversationMemoryRuntime(
    private val memoryService: MemoryService,
    private val captureService: MemoryCaptureService,
    private val contextProvider: DesktopMemoryContextProvider = DesktopMemoryContextProvider(),
) : ConversationMemoryRuntime {
    override suspend fun retrieveMemory(request: MemoryRetrievalRequest): MemoryRetrievalResult {
        val context = contextProvider.current(request.context.conversationId?.value)
        return memoryService.retrieveMemory(
            request.copy(context = context),
            overrideScopes = context.allowedRetrievalScopes(),
        )
    }

    override suspend fun captureCompletedTurn(input: CompletedTurnMemoryInput) {
        val context = contextProvider.current(input.conversationId)
        captureService.captureAfterTurn(
            MemoryCaptureInput(
                context = context,
                scopes = context.allowedRetrievalScopes(),
                userMessage = input.userMessage,
                assistantMessage = input.assistantMessage,
                evidence = input.evidence,
                conversationId = input.conversationId,
                userMessageId = input.userMessageId,
                assistantMessageId = input.assistantMessageId,
            )
        )
    }
}
