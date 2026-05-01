package ru.souz.backend.storage.postgres

import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource
import ru.souz.backend.agent.session.AgentConversationState
import ru.souz.backend.agent.session.AgentStateConflictException
import ru.souz.backend.agent.session.AgentStateRepository

class PostgresAgentStateRepository(
    private val dataSource: DataSource,
) : AgentStateRepository {
    override suspend fun get(userId: String, chatId: UUID): AgentConversationState? = dataSource.read { connection ->
        connection.prepareStatement(
            "select * from agent_conversation_state where user_id = ? and chat_id = ?"
        ).use { statement ->
            statement.setString(1, userId)
            statement.setObject(2, chatId)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.toState() else null
            }
        }
    }

    override suspend fun save(state: AgentConversationState): AgentConversationState = dataSource.write { connection ->
        connection.ensureUser(state.userId)
        connection.ensureStateChat(state.userId, state.chatId, state.updatedAt)
        val updated = connection.prepareStatement(
            """
            update agent_conversation_state
            set context_json = ?,
                based_on_message_seq = ?,
                updated_at = ?,
                row_version = row_version + 1
            where user_id = ? and chat_id = ? and row_version = ?
            """.trimIndent()
        ).use { statement ->
            statement.setJson(1, state.toContextJson())
            statement.setLong(2, state.basedOnMessageSeq)
            statement.setInstant(3, state.updatedAt)
            statement.setString(4, state.userId)
            statement.setObject(5, state.chatId)
            statement.setLong(6, state.rowVersion)
            statement.executeUpdate()
        }
        if (updated == 1) {
            return@write state.copy(rowVersion = state.rowVersion + 1)
        }
        try {
            connection.prepareStatement(
                """
                insert into agent_conversation_state(
                    user_id,
                    chat_id,
                    context_json,
                    based_on_message_seq,
                    updated_at,
                    row_version
                )
                values (?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, state.userId)
                statement.setObject(2, state.chatId)
                statement.setJson(3, state.toContextJson())
                statement.setLong(4, state.basedOnMessageSeq)
                statement.setInstant(5, state.updatedAt)
                statement.setLong(6, state.rowVersion)
                statement.executeUpdate()
            }
            state
        } catch (error: SQLException) {
            if (error.isConstraintViolation(PRIMARY_KEY_CONSTRAINT)) {
                throw AgentStateConflictException(state.userId, state.chatId, state.rowVersion)
            }
            throw error
        }
    }
}
