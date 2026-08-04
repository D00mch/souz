package ru.souz.backend.testutil.repository

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.souz.backend.chat.model.ChatRole
import ru.souz.backend.client.repository.ClientInputRepository
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.backend.execution.repository.AgentExecutionRepository

class MemoryClientInputRepository(
    private val messageRepository: MemoryMessageRepository,
    private val executionRepository: AgentExecutionRepository,
) : ClientInputRepository {
    private val mutex = Mutex()

    override suspend fun appendFollowUpInput(
        execution: AgentExecution,
        content: String,
        metadata: Map<String, String>,
        latestDeviceContextJson: String,
        messageId: UUID,
        createdAt: Instant,
    ): AgentExecution? = mutex.withLock {
        val current = executionRepository.getByChat(execution.userId, execution.chatId, execution.id)
            ?: return@withLock null
        if (!current.status.acceptsFollowUpInput() || current.revision != execution.revision) {
            return@withLock null
        }
        messageRepository.append(
            userId = execution.userId,
            chatId = execution.chatId,
            role = ChatRole.USER,
            content = content,
            metadata = metadata,
            id = messageId,
            createdAt = createdAt,
        )
        executionRepository.update(
            current.copy(
                revision = current.revision + 1,
                latestDeviceContextJson = latestDeviceContextJson,
            )
        )
    }
}

private fun AgentExecutionStatus.acceptsFollowUpInput(): Boolean =
    this == AgentExecutionStatus.QUEUED || this == AgentExecutionStatus.RUNNING
