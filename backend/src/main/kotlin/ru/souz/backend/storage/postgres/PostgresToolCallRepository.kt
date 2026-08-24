package ru.souz.backend.storage.postgres

import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import ru.souz.backend.toolcall.model.ToolCall
import ru.souz.backend.toolcall.repository.ToolCallContext
import ru.souz.backend.toolcall.repository.ToolCallRepository

class PostgresToolCallRepository(
    private val dataSource: DataSource,
) : ToolCallRepository {
    override suspend fun started(
        context: ToolCallContext,
        name: String,
        argumentsPreview: String,
        startedAt: Instant,
    ): ToolCall = dataSource.write { connection ->
        val chatId = context.chatId.toUuid()
        val executionId = context.executionId.toUuid()
        connection.lockChat(context.userId, chatId)
        connection.prepareStatement(
            """
            insert into tool_calls(
              user_id, chat_id, execution_id, tool_call_id, name, target, device_id, status,
              arguments_json, result_json, error_json, deadline_at, result_payload_hash,
              result_received_at, started_at, finished_at, duration_ms
            )
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (user_id, chat_id, execution_id, tool_call_id) do update
            set name = excluded.name,
                target = excluded.target,
                device_id = excluded.device_id,
                status = excluded.status,
                arguments_json = excluded.arguments_json,
                result_json = excluded.result_json,
                error_json = excluded.error_json,
                deadline_at = excluded.deadline_at,
                result_payload_hash = excluded.result_payload_hash,
                result_received_at = excluded.result_received_at,
                started_at = excluded.started_at,
                finished_at = excluded.finished_at,
                duration_ms = excluded.duration_ms
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, context.userId)
            statement.setObject(2, chatId)
            statement.setObject(3, executionId)
            statement.setString(4, context.toolCallId)
            statement.setString(5, name)
            statement.setString(6, "souz")
            statement.setString(7, null)
            statement.setString(8, "running")
            statement.setJson(9, argumentsPreview)
            statement.setJson(10, null)
            statement.setJson(11, null)
            statement.setInstant(12, null)
            statement.setString(13, null)
            statement.setInstant(14, null)
            statement.setInstant(15, startedAt)
            statement.setInstant(16, null)
            statement.setObject(17, null)
            statement.executeUpdate()
        }
        connection.getToolCall(context, chatId, executionId)
            ?: error("Tool call disappeared after starting: ${context.toolCallId}")
    }

    override suspend fun finished(
        context: ToolCallContext,
        name: String,
        resultPreview: String?,
        finishedAt: Instant,
        durationMs: Long,
    ): ToolCall = upsertTerminal(
        context = context,
        name = name,
        status = "succeeded",
        resultJson = resultPreview,
        errorJson = null,
        finishedAt = finishedAt,
        durationMs = durationMs,
    )

    override suspend fun failed(
        context: ToolCallContext,
        name: String,
        error: String,
        finishedAt: Instant,
        durationMs: Long,
    ): ToolCall = upsertTerminal(
        context = context,
        name = name,
        status = "failed",
        resultJson = null,
        errorJson = postgresStorageMapper.writeValueAsString(mapOf("message" to error)),
        finishedAt = finishedAt,
        durationMs = durationMs,
    )

    override suspend fun get(context: ToolCallContext): ToolCall? = dataSource.read { connection ->
        val chatId = context.chatId.toUuid()
        val executionId = context.executionId.toUuid()
        connection.prepareStatement(
            """
            select * from tool_calls
            where user_id = ? and chat_id = ? and execution_id = ? and tool_call_id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, context.userId)
            statement.setObject(2, chatId)
            statement.setObject(3, executionId)
            statement.setString(4, context.toolCallId)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.toToolCall() else null
            }
        }
    }

    override suspend fun listByExecution(
        context: ToolCallContext,
        limit: Int,
    ): List<ToolCall> = dataSource.read { connection ->
        val chatId = context.chatId.toUuid()
        val executionId = context.executionId.toUuid()
        connection.prepareStatement(
            """
            select * from tool_calls
            where user_id = ? and chat_id = ? and execution_id = ?
            order by started_at asc, tool_call_id asc
            limit ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, context.userId)
            statement.setObject(2, chatId)
            statement.setObject(3, executionId)
            statement.setInt(4, limit)
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(resultSet.toToolCall())
                    }
                }
            }
        }
    }

    override suspend fun startClientCall(
        context: ToolCallContext,
        name: String,
        deviceId: String?,
        argumentsJson: String,
        deadlineAt: Instant,
        startedAt: Instant,
    ): ToolCall = dataSource.write { connection ->
        val chatId = context.chatId.toUuid()
        val executionId = context.executionId.toUuid()
        connection.lockChat(context.userId, chatId)
        connection.prepareStatement(
            """
            insert into tool_calls(
              user_id, chat_id, execution_id, tool_call_id, name, target, device_id, status,
              arguments_json, deadline_at, started_at
            ) values (?, ?, ?, ?, ?, 'client', ?, 'running', ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, context.userId)
            statement.setObject(2, chatId)
            statement.setObject(3, executionId)
            statement.setString(4, context.toolCallId)
            statement.setString(5, name)
            statement.setString(6, deviceId)
            statement.setJson(7, argumentsJson)
            statement.setInstant(8, deadlineAt)
            statement.setInstant(9, startedAt)
            statement.executeUpdate()
        }
        connection.getToolCall(context, chatId, executionId)!!
    }

    override suspend fun completeClientCall(
        context: ToolCallContext,
        status: ru.souz.backend.toolcall.model.ToolCallStatus,
        resultJson: String?,
        errorJson: String?,
        payloadHash: String,
        receivedAt: Instant,
    ): ToolCall? = dataSource.write { connection ->
        val chatId = context.chatId.toUuid()
        val executionId = context.executionId.toUuid()
        connection.lockChat(context.userId, chatId)
        connection.prepareStatement(
            """
            update tool_calls
            set status = ?, result_json = ?, error_json = ?, result_payload_hash = ?,
                result_received_at = ?, finished_at = ?, duration_ms = greatest(0, extract(epoch from (? - started_at)) * 1000)::bigint
            where user_id = ? and chat_id = ? and execution_id = ? and tool_call_id = ?
              and target = 'client' and status = 'running'
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, status.value)
            statement.setJson(2, resultJson)
            statement.setJson(3, errorJson)
            statement.setString(4, payloadHash)
            statement.setInstant(5, receivedAt)
            statement.setInstant(6, receivedAt)
            statement.setInstant(7, receivedAt)
            statement.setString(8, context.userId)
            statement.setObject(9, chatId)
            statement.setObject(10, executionId)
            statement.setString(11, context.toolCallId)
            if (statement.executeUpdate() == 0) return@write null
        }
        connection.getToolCall(context, chatId, executionId)
    }

    private suspend fun upsertTerminal(
        context: ToolCallContext,
        name: String,
        status: String,
        resultJson: String?,
        errorJson: String?,
        finishedAt: Instant,
        durationMs: Long,
    ): ToolCall = dataSource.write { connection ->
        val chatId = context.chatId.toUuid()
        val executionId = context.executionId.toUuid()
        connection.lockChat(context.userId, chatId)
        connection.prepareStatement(
            """
            insert into tool_calls(
              user_id, chat_id, execution_id, tool_call_id, name, target, status,
              arguments_json, result_json, error_json, started_at, finished_at, duration_ms
            )
            values (?, ?, ?, ?, ?, 'souz', ?, ?, ?, ?, ?, ?, ?)
            on conflict (user_id, chat_id, execution_id, tool_call_id) do update
            set name = excluded.name,
                status = excluded.status,
                result_json = excluded.result_json,
                error_json = excluded.error_json,
                finished_at = excluded.finished_at,
                duration_ms = excluded.duration_ms
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, context.userId)
            statement.setObject(2, chatId)
            statement.setObject(3, executionId)
            statement.setString(4, context.toolCallId)
            statement.setString(5, name)
            statement.setString(6, status)
            statement.setJson(7, "{}")
            statement.setJson(8, resultJson)
            statement.setJson(9, errorJson)
            statement.setInstant(10, finishedAt)
            statement.setInstant(11, finishedAt)
            statement.setLong(12, durationMs)
            statement.executeUpdate()
        }
        connection.getToolCall(context, chatId, executionId)
            ?: error("Tool call disappeared after finishing: ${context.toolCallId}")
    }
}

private fun java.sql.Connection.getToolCall(
    context: ToolCallContext,
    chatId: UUID,
    executionId: UUID,
): ToolCall? = prepareStatement(
    """
    select * from tool_calls
    where user_id = ? and chat_id = ? and execution_id = ? and tool_call_id = ?
    """.trimIndent()
).use { statement ->
    statement.setString(1, context.userId)
    statement.setObject(2, chatId)
    statement.setObject(3, executionId)
    statement.setString(4, context.toolCallId)
    statement.executeQuery().use { resultSet ->
        if (resultSet.next()) resultSet.toToolCall() else null
    }
}

private fun String.toUuid(): UUID = UUID.fromString(this)
