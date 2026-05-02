package ru.souz.backend.storage.postgres

import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import ru.souz.backend.chat.model.ChatMessage
import ru.souz.backend.chat.model.ChatRole
import ru.souz.backend.chat.repository.MessageRepository

class PostgresMessageRepository(
    private val dataSource: DataSource,
) : MessageRepository {
    override suspend fun append(
        userId: String,
        chatId: UUID,
        role: ChatRole,
        content: String,
        metadata: Map<String, String>,
        id: UUID,
        createdAt: Instant,
    ): ChatMessage = dataSource.write { connection ->
        connection.lockChat(userId, chatId)
        val nextSeq = connection.prepareStatement(
            "select coalesce(max(seq), 0) + 1 from messages where user_id = ? and chat_id = ?"
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
            insert into messages(id, user_id, chat_id, seq, role, content, metadata, created_at)
            values (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, id)
            statement.setString(2, userId)
            statement.setObject(3, chatId)
            statement.setLong(4, nextSeq)
            statement.setString(5, role.value)
            statement.setString(6, content)
            statement.setJson(7, postgresStorageMapper.writeValueAsString(metadata))
            statement.setInstant(8, createdAt)
            statement.executeUpdate()
        }
        ChatMessage(
            id = id,
            userId = userId,
            chatId = chatId,
            seq = nextSeq,
            role = role,
            content = content,
            metadata = metadata,
            createdAt = createdAt,
        )
    }

    override suspend fun get(userId: String, chatId: UUID, seq: Long): ChatMessage? =
        dataSource.read { connection ->
            connection.prepareStatement(
                "select * from messages where user_id = ? and chat_id = ? and seq = ?"
            ).use { statement ->
                statement.setString(1, userId)
                statement.setObject(2, chatId)
                statement.setLong(3, seq)
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) resultSet.toMessage() else null
                }
            }
        }

    override suspend fun getById(userId: String, chatId: UUID, messageId: UUID): ChatMessage? =
        dataSource.read { connection ->
            connection.prepareStatement(
                "select * from messages where user_id = ? and chat_id = ? and id = ?"
            ).use { statement ->
                statement.setString(1, userId)
                statement.setObject(2, chatId)
                statement.setObject(3, messageId)
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) resultSet.toMessage() else null
                }
            }
        }

    override suspend fun latest(userId: String, chatId: UUID): ChatMessage? = dataSource.read { connection ->
        connection.prepareStatement(
            """
            select * from messages
            where user_id = ? and chat_id = ?
            order by seq desc
            limit 1
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, userId)
            statement.setObject(2, chatId)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.toMessage() else null
            }
        }
    }

    override suspend fun updateContent(
        userId: String,
        chatId: UUID,
        messageId: UUID,
        content: String,
    ): ChatMessage? = dataSource.write { connection ->
        connection.prepareStatement(
            """
            update messages
            set content = ?
            where user_id = ? and chat_id = ? and id = ?
            returning *
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, content)
            statement.setString(2, userId)
            statement.setObject(3, chatId)
            statement.setObject(4, messageId)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.toMessage() else null
            }
        }
    }

    override suspend fun list(
        userId: String,
        chatId: UUID,
        afterSeq: Long?,
        beforeSeq: Long?,
        limit: Int,
    ): List<ChatMessage> = dataSource.read { connection ->
        val descending = beforeSeq != null
        val sql = buildString {
            append("select * from messages where user_id = ? and chat_id = ?")
            if (afterSeq != null) append(" and seq > ?")
            if (beforeSeq != null) append(" and seq < ?")
            append(" order by seq ")
            append(if (descending) "desc" else "asc")
            append(" limit ?")
        }
        connection.prepareStatement(sql).use { statement ->
            var index = 1
            statement.setString(index++, userId)
            statement.setObject(index++, chatId)
            if (afterSeq != null) {
                statement.setLong(index++, afterSeq)
            }
            if (beforeSeq != null) {
                statement.setLong(index++, beforeSeq)
            }
            statement.setInt(index, limit)
            statement.executeQuery().use { resultSet ->
                val rows = buildList {
                    while (resultSet.next()) {
                        add(resultSet.toMessage())
                    }
                }
                if (descending) rows.asReversed() else rows
            }
        }
    }
}
