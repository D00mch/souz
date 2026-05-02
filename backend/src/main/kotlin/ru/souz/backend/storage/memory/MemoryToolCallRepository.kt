package ru.souz.backend.storage.memory

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.souz.backend.toolcall.model.ToolCall
import ru.souz.backend.toolcall.model.ToolCallStatus
import ru.souz.backend.toolcall.repository.ToolCallRepository

class MemoryToolCallRepository(
    maxEntries: Int,
) : ToolCallRepository {
    private val mutex = Mutex()
    private val toolCalls = boundedLruMap<ToolCallKey, ToolCall>(maxEntries)

    constructor() : this(DEFAULT_MEMORY_REPOSITORY_MAX_ENTRIES)

    override suspend fun started(
        userId: String,
        chatId: UUID,
        executionId: UUID,
        toolCallId: String,
        name: String,
        argumentsJson: String,
        startedAt: Instant,
    ): ToolCall = mutex.withLock {
        val record = ToolCall(
            userId = userId,
            chatId = chatId,
            executionId = executionId,
            toolCallId = toolCallId,
            name = name,
            status = ToolCallStatus.RUNNING,
            argumentsJson = argumentsJson,
            startedAt = startedAt,
        )
        toolCalls[ToolCallKey(userId, chatId, executionId, toolCallId)] = record
        record
    }

    override suspend fun finished(
        userId: String,
        chatId: UUID,
        executionId: UUID,
        toolCallId: String,
        name: String,
        resultPreview: String?,
        finishedAt: Instant,
        durationMs: Long,
    ): ToolCall = mutex.withLock {
        val key = ToolCallKey(userId, chatId, executionId, toolCallId)
        val current = toolCalls[key]
        val record = (current ?: ToolCall(
            userId = userId,
            chatId = chatId,
            executionId = executionId,
            toolCallId = toolCallId,
            name = name,
            status = ToolCallStatus.RUNNING,
            argumentsJson = "{}",
            startedAt = finishedAt,
        )).copy(
            name = name,
            status = ToolCallStatus.FINISHED,
            resultPreview = resultPreview,
            error = null,
            finishedAt = finishedAt,
            durationMs = durationMs,
        )
        toolCalls[key] = record
        record
    }

    override suspend fun failed(
        userId: String,
        chatId: UUID,
        executionId: UUID,
        toolCallId: String,
        name: String,
        error: String,
        finishedAt: Instant,
        durationMs: Long,
    ): ToolCall = mutex.withLock {
        val key = ToolCallKey(userId, chatId, executionId, toolCallId)
        val current = toolCalls[key]
        val record = (current ?: ToolCall(
            userId = userId,
            chatId = chatId,
            executionId = executionId,
            toolCallId = toolCallId,
            name = name,
            status = ToolCallStatus.RUNNING,
            argumentsJson = "{}",
            startedAt = finishedAt,
        )).copy(
            name = name,
            status = ToolCallStatus.FAILED,
            resultPreview = null,
            error = error,
            finishedAt = finishedAt,
            durationMs = durationMs,
        )
        toolCalls[key] = record
        record
    }

    override suspend fun get(
        userId: String,
        chatId: UUID,
        executionId: UUID,
        toolCallId: String,
    ): ToolCall? = mutex.withLock {
        toolCalls[ToolCallKey(userId, chatId, executionId, toolCallId)]
    }

    override suspend fun listByExecution(
        userId: String,
        chatId: UUID,
        executionId: UUID,
        limit: Int,
    ): List<ToolCall> = mutex.withLock {
        toolCalls.values
            .asSequence()
            .filter { toolCall ->
                toolCall.userId == userId &&
                    toolCall.chatId == chatId &&
                    toolCall.executionId == executionId
            }
            .sortedWith(compareBy<ToolCall> { it.startedAt }.thenBy { it.toolCallId })
            .take(limit)
            .toList()
    }
}

private data class ToolCallKey(
    val userId: String,
    val chatId: UUID,
    val executionId: UUID,
    val toolCallId: String,
)
