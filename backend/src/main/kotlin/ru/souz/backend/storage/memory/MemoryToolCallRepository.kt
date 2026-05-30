package ru.souz.backend.storage.memory

import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.souz.backend.toolcall.model.ToolCall
import ru.souz.backend.toolcall.model.ToolCallStatus
import ru.souz.backend.toolcall.repository.ToolCallContext
import ru.souz.backend.toolcall.repository.ToolCallRepository

class MemoryToolCallRepository(
    maxEntries: Int,
) : ToolCallRepository {
    private val mutex = Mutex()
    private val toolCalls = boundedLruMap<ToolCallKey, ToolCall>(maxEntries)

    constructor() : this(DEFAULT_MEMORY_REPOSITORY_MAX_ENTRIES)

    override suspend fun started(
        context: ToolCallContext,
        name: String,
        argumentsPreview: String,
        startedAt: Instant,
    ): ToolCall = mutex.withLock {
        val record = ToolCall(
            userId = context.userId,
            chatId = context.chatId,
            executionId = context.executionId,
            toolCallId = context.toolCallId,
            name = name,
            status = ToolCallStatus.RUNNING,
            argumentsJson = argumentsPreview,
            startedAt = startedAt,
        )
        toolCalls[context.toKey()] = record
        record
    }

    override suspend fun finished(
        context: ToolCallContext,
        name: String,
        resultPreview: String?,
        finishedAt: Instant,
        durationMs: Long,
    ): ToolCall = mutex.withLock {
        val key = context.toKey()
        val current = toolCalls[key]
        val record = (current ?: ToolCall(
            userId = context.userId,
            chatId = context.chatId,
            executionId = context.executionId,
            toolCallId = context.toolCallId,
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
        context: ToolCallContext,
        name: String,
        error: String,
        finishedAt: Instant,
        durationMs: Long,
    ): ToolCall = mutex.withLock {
        val key = context.toKey()
        val current = toolCalls[key]
        val record = (current ?: ToolCall(
            userId = context.userId,
            chatId = context.chatId,
            executionId = context.executionId,
            toolCallId = context.toolCallId,
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

    override suspend fun get(context: ToolCallContext): ToolCall? = mutex.withLock {
        toolCalls[context.toKey()]
    }

    override suspend fun listByExecution(
        context: ToolCallContext,
        limit: Int,
    ): List<ToolCall> = mutex.withLock {
        toolCalls.values
            .asSequence()
            .filter { toolCall ->
                toolCall.userId == context.userId &&
                    toolCall.chatId == context.chatId &&
                    toolCall.executionId == context.executionId
            }
            .sortedWith(compareBy<ToolCall> { it.startedAt }.thenBy { it.toolCallId })
            .take(limit)
            .toList()
    }
}

private data class ToolCallKey(
    val userId: String,
    val chatId: String,
    val executionId: String,
    val toolCallId: String,
)

private fun ToolCallContext.toKey(): ToolCallKey =
    ToolCallKey(
        userId = userId,
        chatId = chatId,
        executionId = executionId,
        toolCallId = toolCallId,
    )
