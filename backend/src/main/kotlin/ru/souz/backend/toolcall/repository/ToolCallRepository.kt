package ru.souz.backend.toolcall.repository

import java.time.Instant
import ru.souz.backend.toolcall.model.ToolCall

data class ToolCallContext(
    val userId: String,
    val chatId: String,
    val executionId: String,
    val toolCallId: String,
)

interface ToolCallRepository {
    suspend fun started(
        context: ToolCallContext,
        name: String,
        argumentsPreview: String,
        startedAt: Instant = Instant.now(),
    ): ToolCall

    suspend fun finished(
        context: ToolCallContext,
        name: String,
        resultPreview: String?,
        finishedAt: Instant = Instant.now(),
        durationMs: Long,
    ): ToolCall

    suspend fun failed(
        context: ToolCallContext,
        name: String,
        error: String,
        finishedAt: Instant = Instant.now(),
        durationMs: Long,
    ): ToolCall

    suspend fun get(context: ToolCallContext): ToolCall?

    suspend fun listByExecution(
        context: ToolCallContext,
        limit: Int = DEFAULT_LIMIT,
    ): List<ToolCall>

    companion object {
        const val DEFAULT_LIMIT: Int = 100
    }
}
