package ru.souz.backend.toolcall.repository

import java.time.Instant
import java.util.UUID
import ru.souz.backend.toolcall.model.ToolCall

interface ToolCallRepository {
    suspend fun started(
        userId: String,
        chatId: UUID,
        executionId: UUID,
        toolCallId: String,
        name: String,
        argumentsJson: String,
        startedAt: Instant = Instant.now(),
    ): ToolCall

    suspend fun finished(
        userId: String,
        chatId: UUID,
        executionId: UUID,
        toolCallId: String,
        name: String,
        resultPreview: String?,
        finishedAt: Instant = Instant.now(),
        durationMs: Long,
    ): ToolCall

    suspend fun failed(
        userId: String,
        chatId: UUID,
        executionId: UUID,
        toolCallId: String,
        name: String,
        error: String,
        finishedAt: Instant = Instant.now(),
        durationMs: Long,
    ): ToolCall

    suspend fun get(
        userId: String,
        chatId: UUID,
        executionId: UUID,
        toolCallId: String,
    ): ToolCall?

    suspend fun listByExecution(
        userId: String,
        chatId: UUID,
        executionId: UUID,
        limit: Int = DEFAULT_LIMIT,
    ): List<ToolCall>

    companion object {
        const val DEFAULT_LIMIT: Int = 100
    }
}
