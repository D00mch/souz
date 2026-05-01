package ru.souz.backend.storage.postgres

import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.execution.model.isActive
import ru.souz.backend.execution.repository.ActiveAgentExecutionConflictException
import ru.souz.backend.execution.repository.AgentExecutionRepository

class PostgresAgentExecutionRepository(
    private val dataSource: DataSource,
) : AgentExecutionRepository {
    override suspend fun create(execution: AgentExecution): AgentExecution = dataSource.write { connection ->
        connection.ensureUser(execution.userId)
        try {
            connection.prepareStatement(
                """
                insert into agent_executions(
                    id,
                    user_id,
                    chat_id,
                    user_message_id,
                    assistant_message_id,
                    status,
                    request_id,
                    client_message_id,
                    model,
                    provider,
                    started_at,
                    finished_at,
                    cancel_requested,
                    error_code,
                    error_message,
                    usage_json,
                    metadata
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                bindExecution(statement, execution)
                statement.executeUpdate()
            }
        } catch (error: SQLException) {
            if (error.isConstraintViolation(ACTIVE_EXECUTION_CONSTRAINT)) {
                throw ActiveAgentExecutionConflictException(execution.userId, execution.chatId)
            }
            throw error
        }
        execution
    }

    override suspend fun update(execution: AgentExecution): AgentExecution = dataSource.write { connection ->
        try {
            connection.prepareStatement(
                """
                update agent_executions
                set user_message_id = ?,
                    assistant_message_id = ?,
                    status = ?,
                    request_id = ?,
                    client_message_id = ?,
                    model = ?,
                    provider = ?,
                    started_at = ?,
                    finished_at = ?,
                    cancel_requested = ?,
                    error_code = ?,
                    error_message = ?,
                    usage_json = ?,
                    metadata = ?
                where user_id = ? and id = ?
                """.trimIndent()
            ).use { statement ->
                bindExecutionUpdate(statement, execution)
                statement.executeUpdate()
            }
        } catch (error: SQLException) {
            if (error.isConstraintViolation(ACTIVE_EXECUTION_CONSTRAINT) && execution.status.isActive()) {
                throw ActiveAgentExecutionConflictException(execution.userId, execution.chatId)
            }
            throw error
        }
        execution
    }

    override suspend fun get(userId: String, executionId: UUID): AgentExecution? = dataSource.read { connection ->
        connection.prepareStatement(
            "select * from agent_executions where user_id = ? and id = ?"
        ).use { statement ->
            statement.setString(1, userId)
            statement.setObject(2, executionId)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.toExecution() else null
            }
        }
    }

    override suspend fun getByChat(
        userId: String,
        chatId: UUID,
        executionId: UUID,
    ): AgentExecution? = dataSource.read { connection ->
        connection.prepareStatement(
            "select * from agent_executions where user_id = ? and chat_id = ? and id = ?"
        ).use { statement ->
            statement.setString(1, userId)
            statement.setObject(2, chatId)
            statement.setObject(3, executionId)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.toExecution() else null
            }
        }
    }

    override suspend fun findActive(userId: String, chatId: UUID): AgentExecution? = dataSource.read { connection ->
        connection.prepareStatement(
            """
            select * from agent_executions
            where user_id = ? and chat_id = ?
              and status in ('queued', 'running', 'waiting_choice', 'cancelling')
            order by started_at desc
            limit 1
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, userId)
            statement.setObject(2, chatId)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.toExecution() else null
            }
        }
    }

    override suspend fun listByChat(
        userId: String,
        chatId: UUID,
        limit: Int,
    ): List<AgentExecution> = dataSource.read { connection ->
        connection.prepareStatement(
            """
            select * from agent_executions
            where user_id = ? and chat_id = ?
            order by started_at desc
            limit ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, userId)
            statement.setObject(2, chatId)
            statement.setInt(3, limit)
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(resultSet.toExecution())
                    }
                }
            }
        }
    }

    private fun bindExecution(statement: java.sql.PreparedStatement, execution: AgentExecution) {
        statement.setObject(1, execution.id)
        statement.setString(2, execution.userId)
        statement.setObject(3, execution.chatId)
        statement.setObject(4, execution.userMessageId)
        statement.setObject(5, execution.assistantMessageId)
        statement.setString(6, execution.status.value)
        statement.setString(7, execution.requestId)
        statement.setString(8, execution.clientMessageId)
        statement.setString(9, execution.model?.alias)
        statement.setString(10, execution.provider?.name)
        statement.setInstant(11, execution.startedAt)
        statement.setInstant(12, execution.finishedAt)
        statement.setBoolean(13, execution.cancelRequested)
        statement.setString(14, execution.errorCode)
        statement.setString(15, execution.errorMessage)
        statement.setJson(16, execution.usage?.toUsageJson())
        statement.setJson(17, postgresStorageMapper.writeValueAsString(execution.metadata))
    }

    private fun bindExecutionUpdate(statement: java.sql.PreparedStatement, execution: AgentExecution) {
        statement.setObject(1, execution.userMessageId)
        statement.setObject(2, execution.assistantMessageId)
        statement.setString(3, execution.status.value)
        statement.setString(4, execution.requestId)
        statement.setString(5, execution.clientMessageId)
        statement.setString(6, execution.model?.alias)
        statement.setString(7, execution.provider?.name)
        statement.setInstant(8, execution.startedAt)
        statement.setInstant(9, execution.finishedAt)
        statement.setBoolean(10, execution.cancelRequested)
        statement.setString(11, execution.errorCode)
        statement.setString(12, execution.errorMessage)
        statement.setJson(13, execution.usage?.toUsageJson())
        statement.setJson(14, postgresStorageMapper.writeValueAsString(execution.metadata))
        statement.setString(15, execution.userId)
        statement.setObject(16, execution.id)
    }
}
