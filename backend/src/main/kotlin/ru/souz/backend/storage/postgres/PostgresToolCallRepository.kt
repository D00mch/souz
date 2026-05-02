package ru.souz.backend.storage.postgres

import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import ru.souz.backend.toolcall.model.ToolCall
import ru.souz.backend.toolcall.repository.ToolCallRepository

class PostgresToolCallRepository(
    private val dataSource: DataSource,
) : ToolCallRepository {
    override suspend fun started(
        userId: String,
        chatId: UUID,
        executionId: UUID,
        toolCallId: String,
        name: String,
        argumentsJson: String,
        startedAt: Instant,
    ): ToolCall = dataSource.write { connection ->
        connection.ensureUser(userId)
        connection.lockChat(userId, chatId)
        connection.prepareStatement(
            """
            insert into tool_calls(
              user_id, chat_id, execution_id, tool_call_id, name, status,
              arguments_json, result_preview, error, started_at, finished_at, duration_ms
            )
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (user_id, chat_id, execution_id, tool_call_id) do update
            set name = excluded.name,
                status = excluded.status,
                arguments_json = excluded.arguments_json,
                result_preview = excluded.result_preview,
                error = excluded.error,
                started_at = excluded.started_at,
                finished_at = excluded.finished_at,
                duration_ms = excluded.duration_ms
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, userId)
            statement.setObject(2, chatId)
            statement.setObject(3, executionId)
            statement.setString(4, toolCallId)
            statement.setString(5, name)
            statement.setString(6, "running")
            statement.setJson(7, argumentsJson)
            statement.setString(8, null)
            statement.setString(9, null)
            statement.setInstant(10, startedAt)
            statement.setInstant(11, null)
            statement.setObject(12, null)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            """
            select * from tool_calls
            where user_id = ? and chat_id = ? and execution_id = ? and tool_call_id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, userId)
            statement.setObject(2, chatId)
            statement.setObject(3, executionId)
            statement.setString(4, toolCallId)
            statement.executeQuery().use { resultSet ->
                resultSet.next()
                resultSet.toToolCall()
            }
        }
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
    ): ToolCall = upsertTerminal(
        userId = userId,
        chatId = chatId,
        executionId = executionId,
        toolCallId = toolCallId,
        name = name,
        status = "finished",
        resultPreview = resultPreview,
        error = null,
        finishedAt = finishedAt,
        durationMs = durationMs,
    )

    override suspend fun failed(
        userId: String,
        chatId: UUID,
        executionId: UUID,
        toolCallId: String,
        name: String,
        error: String,
        finishedAt: Instant,
        durationMs: Long,
    ): ToolCall = upsertTerminal(
        userId = userId,
        chatId = chatId,
        executionId = executionId,
        toolCallId = toolCallId,
        name = name,
        status = "failed",
        resultPreview = null,
        error = error,
        finishedAt = finishedAt,
        durationMs = durationMs,
    )

    override suspend fun get(
        userId: String,
        chatId: UUID,
        executionId: UUID,
        toolCallId: String,
    ): ToolCall? = dataSource.read { connection ->
        connection.prepareStatement(
            """
            select * from tool_calls
            where user_id = ? and chat_id = ? and execution_id = ? and tool_call_id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, userId)
            statement.setObject(2, chatId)
            statement.setObject(3, executionId)
            statement.setString(4, toolCallId)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.toToolCall() else null
            }
        }
    }

    override suspend fun listByExecution(
        userId: String,
        chatId: UUID,
        executionId: UUID,
        limit: Int,
    ): List<ToolCall> = dataSource.read { connection ->
        connection.prepareStatement(
            """
            select * from tool_calls
            where user_id = ? and chat_id = ? and execution_id = ?
            order by started_at asc, tool_call_id asc
            limit ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, userId)
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

    private suspend fun upsertTerminal(
        userId: String,
        chatId: UUID,
        executionId: UUID,
        toolCallId: String,
        name: String,
        status: String,
        resultPreview: String?,
        error: String?,
        finishedAt: Instant,
        durationMs: Long,
    ): ToolCall = dataSource.write { connection ->
        connection.ensureUser(userId)
        connection.lockChat(userId, chatId)
        connection.prepareStatement(
            """
            insert into tool_calls(
              user_id, chat_id, execution_id, tool_call_id, name, status,
              arguments_json, result_preview, error, started_at, finished_at, duration_ms
            )
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (user_id, chat_id, execution_id, tool_call_id) do update
            set name = excluded.name,
                status = excluded.status,
                result_preview = excluded.result_preview,
                error = excluded.error,
                finished_at = excluded.finished_at,
                duration_ms = excluded.duration_ms
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, userId)
            statement.setObject(2, chatId)
            statement.setObject(3, executionId)
            statement.setString(4, toolCallId)
            statement.setString(5, name)
            statement.setString(6, status)
            statement.setJson(7, "{}")
            statement.setString(8, resultPreview)
            statement.setString(9, error)
            statement.setInstant(10, finishedAt)
            statement.setInstant(11, finishedAt)
            statement.setLong(12, durationMs)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            """
            select * from tool_calls
            where user_id = ? and chat_id = ? and execution_id = ? and tool_call_id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, userId)
            statement.setObject(2, chatId)
            statement.setObject(3, executionId)
            statement.setString(4, toolCallId)
            statement.executeQuery().use { resultSet ->
                resultSet.next()
                resultSet.toToolCall()
            }
        }
    }
}
