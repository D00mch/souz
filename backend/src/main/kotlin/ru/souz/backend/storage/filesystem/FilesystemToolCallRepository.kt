package ru.souz.backend.storage.filesystem

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID
import java.util.LinkedHashMap
import ru.souz.backend.toolcall.model.ToolCall
import ru.souz.backend.toolcall.model.ToolCallStatus
import ru.souz.backend.toolcall.repository.ToolCallRepository

class FilesystemToolCallRepository(
    dataDir: java.nio.file.Path,
    mapper: ObjectMapper = filesystemStorageObjectMapper(),
) : BaseFilesystemRepository(dataDir, mapper), ToolCallRepository {

    override suspend fun started(
        userId: String,
        chatId: UUID,
        executionId: UUID,
        toolCallId: String,
        name: String,
        argumentsJson: String,
        startedAt: Instant,
    ): ToolCall =
        withFileLock {
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
            mapper.appendJsonValue(layout.toolCallsFile(userId, chatId), record.toStored())
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
    ): ToolCall =
        withFileLock {
            val current = loadToolCalls(userId, chatId)
                .firstOrNull { it.executionId == executionId && it.toolCallId == toolCallId }
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
            mapper.appendJsonValue(layout.toolCallsFile(userId, chatId), record.toStored())
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
    ): ToolCall =
        withFileLock {
            val current = loadToolCalls(userId, chatId)
                .firstOrNull { it.executionId == executionId && it.toolCallId == toolCallId }
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
            mapper.appendJsonValue(layout.toolCallsFile(userId, chatId), record.toStored())
            record
        }

    override suspend fun get(
        userId: String,
        chatId: UUID,
        executionId: UUID,
        toolCallId: String,
    ): ToolCall? =
        withFileLock {
            loadToolCalls(userId, chatId)
                .firstOrNull { it.executionId == executionId && it.toolCallId == toolCallId }
        }

    override suspend fun listByExecution(
        userId: String,
        chatId: UUID,
        executionId: UUID,
        limit: Int,
    ): List<ToolCall> =
        withFileLock {
            loadToolCalls(userId, chatId)
                .filter { it.executionId == executionId }
                .sortedWith(compareBy<ToolCall> { it.startedAt }.thenBy { it.toolCallId })
                .take(limit)
        }

    private fun loadToolCalls(
        userId: String,
        chatId: UUID,
    ): List<ToolCall> {
        val snapshots = mapper.readJsonLines<StoredToolCall>(layout.toolCallsFile(userId, chatId))
            .map(StoredToolCall::toDomain)
        val latestByKey = LinkedHashMap<Pair<UUID, String>, ToolCall>()
        snapshots.forEach { toolCall ->
            latestByKey[toolCall.executionId to toolCall.toolCallId] = toolCall
        }
        return latestByKey.values.toList()
    }
}
