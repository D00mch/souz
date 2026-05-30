package ru.souz.backend.storage.postgres

import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import ru.souz.backend.events.model.AgentEvent
import ru.souz.backend.events.model.AgentEventPayload
import ru.souz.backend.events.model.AgentEventPayloadStorageCodec
import ru.souz.backend.events.model.AgentEventType
import ru.souz.backend.events.repository.AgentEventRepository

class PostgresAgentEventRepository(
    private val dataSource: DataSource,
) : AgentEventRepository {
    override suspend fun append(
        userId: String,
        chatId: UUID,
        executionId: UUID?,
        type: AgentEventType,
        payload: AgentEventPayload,
        id: UUID,
        createdAt: Instant,
    ): AgentEvent = dataSource.write { connection ->
        connection.lockChat(userId, chatId)
        val nextSeq = connection.prepareStatement(
            "select coalesce(max(seq), 0) + 1 from agent_events where user_id = ? and chat_id = ?"
        ).use { statement ->
            statement.setString(1, userId)
            statement.setObject(2, chatId)
            statement.executeQuery().use { resultSet ->
                resultSet.next()
                resultSet.getLong(1)
            }
        }
        connection.prepareStatement(
            """
            insert into agent_events(id, user_id, chat_id, execution_id, seq, type, payload, created_at)
            values (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, id)
            statement.setString(2, userId)
            statement.setObject(3, chatId)
            statement.setObject(4, executionId)
            statement.setLong(5, nextSeq)
            statement.setString(6, type.value)
            statement.setJson(7, postgresStorageMapper.writeValueAsString(AgentEventPayloadStorageCodec.toStorageJson(payload)))
            statement.setInstant(8, createdAt)
            statement.executeUpdate()
        }
        AgentEvent(
            id = id,
            userId = userId,
            chatId = chatId,
            executionId = executionId,
            seq = nextSeq,
            type = type,
            payload = payload,
            createdAt = createdAt,
        )
    }

    override suspend fun get(userId: String, eventId: UUID): AgentEvent? = dataSource.read { connection ->
        connection.prepareStatement(
            "select * from agent_events where user_id = ? and id = ?"
        ).use { statement ->
            statement.setString(1, userId)
            statement.setObject(2, eventId)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.toEvent() else null
            }
        }
    }

    override suspend fun listByChat(
        userId: String,
        chatId: UUID,
        afterSeq: Long?,
        limit: Int,
    ): List<AgentEvent> = dataSource.read { connection ->
        val sql = buildString {
            append("select * from agent_events where user_id = ? and chat_id = ?")
            if (afterSeq != null) append(" and seq > ?")
            append(" order by seq asc limit ?")
        }
        connection.prepareStatement(sql).use { statement ->
            var index = 1
            statement.setString(index++, userId)
            statement.setObject(index++, chatId)
            if (afterSeq != null) {
                statement.setLong(index++, afterSeq)
            }
            statement.setInt(index, limit)
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(resultSet.toEvent())
                    }
                }
            }
        }
    }
}
