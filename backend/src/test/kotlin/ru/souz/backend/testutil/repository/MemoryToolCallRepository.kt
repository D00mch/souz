package ru.souz.backend.testutil.repository

import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.souz.backend.toolcall.model.ToolCall
import ru.souz.backend.toolcall.model.ToolCallStatus
import ru.souz.backend.toolcall.repository.ToolCallContext
import ru.souz.backend.toolcall.repository.ToolCallRepository
import ru.souz.llms.restJsonMapper

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
            status = ToolCallStatus.SUCCEEDED,
            resultJson = resultPreview,
            errorJson = null,
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
            resultJson = null,
            errorJson = restJsonMapper.writeValueAsString(mapOf("message" to error)),
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

    override suspend fun startClientCall(
        context: ToolCallContext,
        name: String,
        deviceId: String?,
        argumentsJson: String,
        deadlineAt: Instant,
        startedAt: Instant,
    ): ToolCall = mutex.withLock {
        ToolCall(
            userId = context.userId,
            chatId = context.chatId,
            executionId = context.executionId,
            toolCallId = context.toolCallId,
            name = name,
            target = "client",
            deviceId = deviceId,
            status = ToolCallStatus.RUNNING,
            argumentsJson = argumentsJson,
            deadlineAt = deadlineAt,
            startedAt = startedAt,
        ).also { toolCalls[context.toKey()] = it }
    }

    override suspend fun completeClientCall(
        context: ToolCallContext,
        status: ToolCallStatus,
        resultJson: String?,
        errorJson: String?,
        payloadHash: String,
        receivedAt: Instant,
    ): ToolCall? = mutex.withLock {
        val current = toolCalls[context.toKey()] ?: return@withLock null
        if (current.status != ToolCallStatus.RUNNING || current.target != "client") return@withLock null
        current.copy(
            status = status,
            resultJson = resultJson,
            errorJson = errorJson,
            resultPayloadHash = payloadHash,
            resultReceivedAt = receivedAt,
            finishedAt = receivedAt,
        ).also { toolCalls[context.toKey()] = it }
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
