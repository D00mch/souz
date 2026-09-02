package ru.souz.backend.storage.postgres

import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import ru.souz.backend.chat.model.ChatRole
import ru.souz.backend.client.model.ClientRequest
import ru.souz.backend.client.repository.ClientInputRepository
import ru.souz.backend.client.repository.FollowUpInputResult
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.execution.model.acceptsInput

class PostgresClientInputRepository(
    private val dataSource: DataSource,
) : ClientInputRepository {
    override suspend fun appendFollowUpInput(
        execution: AgentExecution,
        request: ClientRequest,
        content: String,
        metadata: Map<String, String>,
        latestDeviceContextJson: String,
        messageId: UUID,
        createdAt: Instant,
    ): FollowUpInputResult = dataSource.write { connection ->
        require(request.chatId == execution.chatId) { "Client request and execution must belong to the same chat." }
        require(request.threadId == execution.id) { "Client request must reference the continued execution." }
        connection.lockChat(execution.userId, execution.chatId)
        val current = connection.prepareStatement(
            """
            select * from agent_executions
            where user_id = ? and chat_id = ? and id = ?
            for update
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, execution.userId)
            statement.setObject(2, execution.chatId)
            statement.setObject(3, execution.id)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.toExecution() else null
            }
        } ?: return@write FollowUpInputResult.Rejected(
            FollowUpInputResult.RejectionReason.EXECUTION_NOT_FOUND,
            currentExecution = null,
        )

        if (!current.status.acceptsInput()) {
            return@write FollowUpInputResult.Rejected(
                FollowUpInputResult.RejectionReason.NOT_ACCEPTING_INPUT,
                current,
            )
        }
        if (current.revision != execution.revision) {
            return@write FollowUpInputResult.Rejected(
                FollowUpInputResult.RejectionReason.REVISION_MISMATCH,
                current,
            )
        }

        val nextRevision = current.revision + 1
        val updatedExecution = connection.prepareStatement(
            """
            update agent_executions
            set revision = ?,
                latest_device_context = ?
            where user_id = ? and chat_id = ? and id = ?
            returning *
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, nextRevision)
            statement.setJson(2, latestDeviceContextJson)
            statement.setString(3, execution.userId)
            statement.setObject(4, execution.chatId)
            statement.setObject(5, execution.id)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.toExecution() else null
            }
        } ?: error("Execution disappeared while locked.")

        val nextMessageSeq = connection.prepareStatement(
            "select coalesce(max(seq), 0) + 1 from messages where user_id = ? and chat_id = ?"
        ).use { statement ->
            statement.setString(1, execution.userId)
            statement.setObject(2, execution.chatId)
            statement.executeQuery().use { resultSet ->
                resultSet.next()
                resultSet.getLong(1)
            }
        }
        connection.prepareStatement(
            """
            insert into messages(id, user_id, chat_id, seq, role, content, metadata, created_at)
            values (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, messageId)
            statement.setString(2, execution.userId)
            statement.setObject(3, execution.chatId)
            statement.setLong(4, nextMessageSeq)
            statement.setString(5, ChatRole.USER.value)
            statement.setString(6, content)
            statement.setJson(7, postgresStorageMapper.writeValueAsString(metadata))
            statement.setInstant(8, createdAt)
            statement.executeUpdate()
        }

        insertClientRequest(connection, request)

        FollowUpInputResult.Accepted(updatedExecution)
    }
}
