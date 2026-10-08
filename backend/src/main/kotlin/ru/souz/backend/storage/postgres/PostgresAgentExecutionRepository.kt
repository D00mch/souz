package ru.souz.backend.storage.postgres

import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import ru.souz.backend.metrics.BackendMetrics
import ru.souz.backend.agent.session.AgentConversationState
import ru.souz.backend.chat.model.ChatRole
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.backend.execution.model.AgentExecutionUsage
import ru.souz.backend.execution.model.isActive
import ru.souz.backend.execution.repository.ActiveAgentExecutionConflictException
import ru.souz.backend.execution.repository.AgentExecutionRepository
import ru.souz.backend.execution.repository.CommittedAgentTurn

class PostgresAgentExecutionRepository(
    private val dataSource: DataSource,
    private val metrics: BackendMetrics? = null,
) : AgentExecutionRepository {
    override suspend fun create(execution: AgentExecution): AgentExecution = dataSource.write(afterCommit = { metrics?.executions?.committed(it, null) }) { connection ->
        connection.lockChat(execution.userId, execution.chatId)
        insert(connection, execution)
    }

    internal fun insert(connection: Connection, execution: AgentExecution): AgentExecution {
        try {
            connection.prepareStatement(
                """
                insert into agent_executions(
                    id, user_id, chat_id, user_message_id, assistant_message_id, status,
                    request_id, client_message_id, model, provider, started_at, finished_at,
                    cancel_requested, error_code, error_message, usage_json, metadata,
                    latest_device_context, runtime_owner, runtime_lease_until
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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

    override suspend fun transitionIfCurrent(
        expected: AgentExecution,
        status: AgentExecutionStatus,
        errorCode: String?,
        errorMessage: String?,
        usage: AgentExecutionUsage?,
    ): AgentExecution? = dataSource.write(afterCommit = { it?.let { stored -> metrics?.executions?.committed(stored, expected.status) } }) { connection ->
        connection.lockChat(expected.userId, expected.chatId)
        transition(connection, expected, status, errorCode, errorMessage, usage)
    }

    override suspend fun commitTurn(
        expected: AgentExecution,
        state: AgentConversationState,
        usage: AgentExecutionUsage,
        output: String?,
        assistantMessageId: UUID?,
    ): CommittedAgentTurn? {
        require(state.userId == expected.userId && state.chatId == expected.chatId)
        val context = currentCoroutineContext()
        return dataSource.write(afterCommit = { it?.let { turn -> metrics?.executions?.committed(turn.execution, expected.status) } }) { connection ->
            connection.lockChat(expected.userId, expected.chatId)
            val current = connection.findExecution(expected.userId, expected.chatId, expected.id)
                ?: return@write null
            if (current.status != AgentExecutionStatus.RUNNING || current.cancelRequested ||
                current.runtimeOwner != expected.runtimeOwner) return@write null

            val message = output?.let { content ->
                val writer = PostgresMessageRepository(dataSource)
                current.assistantMessageId?.let { id ->
                    checkNotNull(writer.updateContent(connection, current.userId, current.chatId, id, content))
                } ?: writer.append(
                    connection, current.userId, current.chatId, ChatRole.ASSISTANT, content,
                    emptyMap(), requireNotNull(assistantMessageId), Instant.now(),
                )
            }
            val savedState = if (message?.seq == state.basedOnMessageSeq + 1L) {
                state.copy(basedOnMessageSeq = message.seq)
            } else state
            PostgresAgentStateRepository(dataSource).save(connection, savedState)
            if (message != null) {
                PostgresChatRepository(dataSource).touchUpdatedAt(connection, current.userId, current.chatId, message.createdAt)
            }
            val execution = checkNotNull(transition(
                connection, current,
                if (output == null) AgentExecutionStatus.WAITING_OPTION else AgentExecutionStatus.COMPLETED,
                usage = usage, assistantMessageId = message?.id,
            ))
            // JDBC may have waited for a lock while the option handoff was cancelled.
            context.ensureActive()
            CommittedAgentTurn(execution, message, message != null && current.assistantMessageId == null)
        }
    }

    internal fun transition(
        connection: Connection,
        expected: AgentExecution,
        status: AgentExecutionStatus,
        errorCode: String? = null,
        errorMessage: String? = null,
        usage: AgentExecutionUsage? = null,
        assistantMessageId: UUID? = null,
    ): AgentExecution? = connection.prepareStatement(
        """
        update agent_executions
        set status = ?, finished_at = ?, cancel_requested = ?, error_code = ?, error_message = ?,
            usage_json = coalesce(?, usage_json), assistant_message_id = coalesce(?, assistant_message_id),
            runtime_lease_until = case when ? = 'waiting_option' then null else runtime_lease_until end
        where user_id = ? and chat_id = ? and id = ?
          and status = ? and cancel_requested = ? and runtime_owner is not distinct from ?
        returning *
        """.trimIndent()
    ).use { statement ->
        statement.setString(1, status.value)
        statement.setInstant(2, if (status.isActive()) null else Instant.now())
        statement.setBoolean(3, status == AgentExecutionStatus.CANCELLING || status == AgentExecutionStatus.CANCELLED)
        statement.setString(4, errorCode)
        statement.setString(5, errorMessage)
        statement.setJson(6, usage?.toUsageJson())
        statement.setObject(7, assistantMessageId)
        statement.setString(8, status.value)
        statement.setString(9, expected.userId)
        statement.setObject(10, expected.chatId)
        statement.setObject(11, expected.id)
        statement.setString(12, expected.status.value)
        statement.setBoolean(13, expected.cancelRequested)
        statement.setString(14, expected.runtimeOwner)
        statement.executeQuery().use { resultSet ->
            if (resultSet.next()) resultSet.toExecution() else null
        }
    }

    internal fun updateDeviceContext(connection: Connection, execution: AgentExecution, deviceContextJson: String?): AgentExecution =
        connection.prepareStatement(
            "update agent_executions set latest_device_context = ? where user_id = ? and chat_id = ? and id = ? returning *"
        ).use { statement ->
            statement.setJson(1, deviceContextJson)
            statement.setString(2, execution.userId)
            statement.setObject(3, execution.chatId)
            statement.setObject(4, execution.id)
            statement.executeQuery().use { resultSet ->
                check(resultSet.next())
                resultSet.toExecution()
            }
        }

    override suspend fun start(execution: AgentExecution, userMessageId: UUID): AgentExecution? =
        dataSource.write(afterCommit = { it?.let { stored -> metrics?.executions?.committed(stored, AgentExecutionStatus.QUEUED) } }) { connection ->
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
        connection.findExecution(userId, chatId, executionId, lock = false)
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
        connection.findActiveExecution(userId, chatId, lock = false)
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
              and status in ('queued', 'running', 'cancelling')
              and runtime_owner = ? and runtime_lease_until > now()
            returning *
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, runtimeOwner)
            statement.setInstant(2, leaseUntil)
            statement.setString(3, userId)
            statement.setObject(4, chatId)
            statement.setObject(5, executionId)
            statement.setString(6, runtimeOwner)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.toExecution() else null
            }
        }
    }

    override suspend fun failInterruptedClientThreads(now: Instant): List<AgentExecution> = dataSource.write(afterCommit = { rows -> rows.forEach { metrics?.executions?.committed(it, AgentExecutionStatus.RUNNING) } }) { connection ->
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
              and execution.status in ('queued', 'running', 'cancelling')
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
        statement.setJson(18, execution.latestDeviceContextJson)
        statement.setString(19, execution.runtimeOwner)
        statement.setInstant(20, execution.runtimeLeaseUntil)
    }

}
