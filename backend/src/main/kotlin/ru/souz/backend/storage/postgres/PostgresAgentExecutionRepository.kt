package ru.souz.backend.storage.postgres

import java.sql.SQLException
import java.time.Instant
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
        connection.lockChat(execution.userId, execution.chatId)
        insert(connection, execution)
    }

    internal fun insert(connection: java.sql.Connection, execution: AgentExecution): AgentExecution {
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
                    metadata,
                    revision,
                    latest_device_context,
                    runtime_owner,
                    runtime_lease_until
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
        return execution
    }

    override suspend fun update(execution: AgentExecution): AgentExecution = dataSource.write { connection ->
        update(connection, execution)
    }

    internal fun update(connection: java.sql.Connection, execution: AgentExecution): AgentExecution {
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
                    metadata = ?,
                    revision = ?,
                    latest_device_context = ?,
                    runtime_owner = ?,
                    runtime_lease_until = ?
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
        return execution
    }

    override suspend fun start(execution: AgentExecution, userMessageId: UUID): AgentExecution? =
        dataSource.write { connection ->
            connection.prepareStatement(
                """
                update agent_executions
                set user_message_id = ?, status = 'running', started_at = now()
                where user_id = ? and chat_id = ? and id = ? and status = 'queued'
                returning *
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, userMessageId)
                statement.setString(2, execution.userId)
                statement.setObject(3, execution.chatId)
                statement.setObject(4, execution.id)
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) resultSet.toExecution() else null
                }
            }
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

    override suspend fun findByClientMessageId(
        userId: String,
        chatId: UUID,
        clientMessageId: String,
    ): AgentExecution? = dataSource.read { connection ->
        connection.prepareStatement(
            """
            select * from agent_executions
            where user_id = ? and chat_id = ? and client_message_id = ?
            order by started_at desc
            limit 1
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, userId)
            statement.setObject(2, chatId)
            statement.setString(3, clientMessageId)
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
              and status in ('queued', 'running', 'waiting_option', 'cancelling')
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

    override suspend fun refreshClientThreadLease(
        userId: String,
        chatId: UUID,
        executionId: UUID,
        runtimeOwner: String,
        leaseUntil: Instant,
    ): AgentExecution? = dataSource.write { connection ->
        connection.prepareStatement(
            """
            update agent_executions
            set runtime_owner = ?, runtime_lease_until = ?
            where user_id = ? and chat_id = ? and id = ?
              and status in ('queued', 'running', 'waiting_option', 'cancelling')
            returning *
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, runtimeOwner)
            statement.setInstant(2, leaseUntil)
            statement.setString(3, userId)
            statement.setObject(4, chatId)
            statement.setObject(5, executionId)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.toExecution() else null
            }
        }
    }

    override suspend fun failInterruptedClientThreads(now: Instant): List<AgentExecution> = dataSource.write { connection ->
        connection.prepareStatement(
            """
            update agent_executions execution
            set status = 'failed',
                finished_at = ?,
                error_code = 'process_restarted',
                error_message = 'The Souz process restarted while the thread was running.'
            from chats chat
            where execution.chat_id = chat.id
              and chat.payload_hash not like 'internal:%'
              and execution.status in ('queued', 'running', 'waiting_option', 'cancelling')
              and execution.runtime_lease_until is not null
              and execution.runtime_lease_until < ?
            returning execution.*
            """.trimIndent()
        ).use { statement ->
            statement.setInstant(1, now)
            statement.setInstant(2, now)
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) add(resultSet.toExecution())
                }
            }
        }
    }

    override suspend fun findRecoveredClientThreadsMissingTerminalEvents(): List<AgentExecution> = dataSource.read { connection ->
        connection.prepareStatement(
            """
            select execution.*
            from agent_executions execution
            join chats chat on chat.id = execution.chat_id
            where chat.payload_hash not like 'internal:%'
              and execution.status = 'failed'
              and execution.error_code = 'process_restarted'
              and not exists (
                select 1 from agent_events event
                where event.execution_id = execution.id
                  and event.type in ('thread.completed', 'thread.failed', 'thread.cancelled')
              )
            order by execution.started_at asc
            """.trimIndent()
        ).use { statement ->
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) add(resultSet.toExecution())
                }
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
        statement.setLong(18, execution.revision)
        statement.setJson(19, execution.latestDeviceContextJson)
        statement.setString(20, execution.runtimeOwner)
        statement.setInstant(21, execution.runtimeLeaseUntil)
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
        statement.setLong(15, execution.revision)
        statement.setJson(16, execution.latestDeviceContextJson)
        statement.setString(17, execution.runtimeOwner)
        statement.setInstant(18, execution.runtimeLeaseUntil)
        statement.setString(19, execution.userId)
        statement.setObject(20, execution.id)
    }
}
