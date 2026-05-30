package ru.souz.backend.storage.filesystem

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.LinkedHashMap
import ru.souz.backend.toolcall.model.ToolCall
import ru.souz.backend.toolcall.model.ToolCallStatus
import ru.souz.backend.toolcall.repository.ToolCallContext
import ru.souz.backend.toolcall.repository.ToolCallRepository

class FilesystemToolCallRepository(
    dataDir: java.nio.file.Path,
    mapper: ObjectMapper = filesystemStorageObjectMapper(),
) : BaseFilesystemRepository(dataDir, mapper), ToolCallRepository {

    override suspend fun started(
        context: ToolCallContext,
        name: String,
        argumentsPreview: String,
        startedAt: Instant,
    ): ToolCall =
        withFileLock {
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
            mapper.appendJsonValue(layout.toolCallsFile(context.userId, context.chatId), record.toStored())
            record
        }

    override suspend fun finished(
        context: ToolCallContext,
        name: String,
        resultPreview: String?,
        finishedAt: Instant,
        durationMs: Long,
    ): ToolCall =
        withFileLock {
            val current = loadToolCalls(context.userId, context.chatId)
                .firstOrNull { it.executionId == context.executionId && it.toolCallId == context.toolCallId }
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
            mapper.appendJsonValue(layout.toolCallsFile(context.userId, context.chatId), record.toStored())
            record
        }

    override suspend fun failed(
        context: ToolCallContext,
        name: String,
        error: String,
        finishedAt: Instant,
        durationMs: Long,
    ): ToolCall =
        withFileLock {
            val current = loadToolCalls(context.userId, context.chatId)
                .firstOrNull { it.executionId == context.executionId && it.toolCallId == context.toolCallId }
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
            mapper.appendJsonValue(layout.toolCallsFile(context.userId, context.chatId), record.toStored())
            record
        }

    override suspend fun get(context: ToolCallContext): ToolCall? =
        withFileLock {
            loadToolCalls(context.userId, context.chatId)
                .firstOrNull { it.executionId == context.executionId && it.toolCallId == context.toolCallId }
        }

    override suspend fun listByExecution(
        context: ToolCallContext,
        limit: Int,
    ): List<ToolCall> =
        withFileLock {
            loadToolCalls(context.userId, context.chatId)
                .filter { it.executionId == context.executionId }
                .sortedWith(compareBy<ToolCall> { it.startedAt }.thenBy { it.toolCallId })
                .take(limit)
        }

    private fun loadToolCalls(
        userId: String,
        chatId: String,
    ): List<ToolCall> {
        val snapshots = mapper.readJsonLines<StoredToolCall>(layout.toolCallsFile(userId, chatId))
            .map(StoredToolCall::toDomain)
        val latestByKey = LinkedHashMap<Pair<String, String>, ToolCall>()
        snapshots.forEach { toolCall ->
            latestByKey[toolCall.executionId to toolCall.toolCallId] = toolCall
        }
        return latestByKey.values.toList()
    }
}
