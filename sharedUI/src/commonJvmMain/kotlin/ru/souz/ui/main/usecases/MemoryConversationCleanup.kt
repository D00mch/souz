package ru.souz.ui.main.usecases

import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import ru.souz.memory.MemoryOwnerProvider
import ru.souz.memory.MemoryScope
import ru.souz.memory.MemoryService

interface MemoryConversationCleanup {
    suspend fun cleanupConversation(conversationId: String)
}

object NoopMemoryConversationCleanup : MemoryConversationCleanup {
    override suspend fun cleanupConversation(conversationId: String) = Unit
}

class MemoryServiceConversationCleanup(
    private val memoryService: MemoryService,
    private val ownerProvider: MemoryOwnerProvider,
    private val onFailure: (conversationId: String, error: Throwable) -> Unit = { conversationId, error ->
        cleanupLogger.warn("Memory conversation cleanup failed for conversationId={}", conversationId, error)
    },
) : MemoryConversationCleanup {
    override suspend fun cleanupConversation(conversationId: String) {
        if (conversationId.isBlank()) return
        try {
            val ownerId = ownerProvider.currentOwnerId()
            val sessionScope = MemoryScope("session", conversationId)
            val chatScope = MemoryScope("chat", conversationId)
            memoryService.closeScopeForCapture(ownerId, sessionScope)
            memoryService.closeScopeForCapture(ownerId, chatScope)
            memoryService.deleteFactsByScope(ownerId, sessionScope)
            memoryService.deleteFactsByScope(ownerId, chatScope)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            onFailure(conversationId, error)
        }
    }
}

private val cleanupLogger = LoggerFactory.getLogger(MemoryServiceConversationCleanup::class.java)
