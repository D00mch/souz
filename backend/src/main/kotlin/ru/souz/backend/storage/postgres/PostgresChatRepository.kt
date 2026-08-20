package ru.souz.backend.storage.postgres

import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.chat.repository.ChatRequestConflictException
import ru.souz.backend.chat.repository.ChatRepository

class PostgresChatRepository(
    private val dataSource: DataSource,
) : ChatRepository {
    override suspend fun create(chat: Chat): Chat = dataSource.write { connection ->
        try {
            connection.prepareStatement(
                """
                insert into chats(
                  id, user_id, client_type, request_id, payload_hash,
                  title, archived, created_at, updated_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, chat.id)
                statement.setString(2, chat.userId)
                statement.setString(3, chat.clientType)
                statement.setString(4, chat.requestId)
                statement.setString(5, chat.payloadHash)
                statement.setString(6, chat.title)
                statement.setBoolean(7, chat.archived)
                statement.setInstant(8, chat.createdAt)
                statement.setInstant(9, chat.updatedAt)
                statement.executeUpdate()
            }
        } catch (error: java.sql.SQLException) {
            if (error.isConstraintViolation(CHAT_REQUEST_CONSTRAINT)) {
                throw ChatRequestConflictException(chat.userId, chat.requestId)
            }
            throw error
        }
        chat
    }

    override suspend fun get(userId: String, chatId: UUID): Chat? = dataSource.read { connection ->
        connection.prepareStatement(
            "select * from chats where user_id = ? and id = ?"
        ).use { statement ->
            statement.setString(1, userId)
            statement.setObject(2, chatId)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.toChat() else null
            }
        }
    }

    override suspend fun getById(chatId: UUID): Chat? = dataSource.read { connection ->
        connection.prepareStatement("select * from chats where id = ?").use { statement ->
            statement.setObject(1, chatId)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.toChat() else null
            }
        }
    }

    override suspend fun getByIds(chatIds: List<UUID>): List<Chat> {
        if (chatIds.isEmpty()) return emptyList()
        return dataSource.read { connection ->
            connection.prepareStatement("select * from chats where id = any(?)").use { statement ->
                statement.setArray(1, connection.createArrayOf("uuid", chatIds.toTypedArray()))
                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) {
                            add(resultSet.toChat())
                        }
                    }
                }
            }
        }
    }

    override suspend fun findByRequestId(userId: String, requestId: String): Chat? = dataSource.read { connection ->
        connection.prepareStatement(
            "select * from chats where user_id = ? and request_id = ?"
        ).use { statement ->
            statement.setString(1, userId)
            statement.setString(2, requestId)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.toChat() else null
            }
        }
    }

    override suspend fun list(
        userId: String,
        limit: Int,
        includeArchived: Boolean,
    ): List<Chat> = dataSource.read { connection ->
        connection.prepareStatement(
            """
            select * from chats
            where user_id = ?
              and (? or archived = false)
            order by updated_at desc
            limit ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, userId)
            statement.setBoolean(2, includeArchived)
            statement.setInt(3, limit)
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(resultSet.toChat())
                    }
                }
            }
        }
    }

    override suspend fun touchUpdatedAt(userId: String, chatId: UUID, updatedAt: Instant) {
        dataSource.write { connection ->
            connection.prepareStatement(
                """
                update chats
                set updated_at = greatest(updated_at, ?)
                where user_id = ? and id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setInstant(1, updatedAt)
                statement.setString(2, userId)
                statement.setObject(3, chatId)
                statement.executeUpdate()
            }
        }
    }

    override suspend fun updateTitle(
        userId: String,
        chatId: UUID,
        title: String,
        updatedAt: Instant,
    ): Chat? = dataSource.write { connection ->
        connection.prepareStatement(
            """
            update chats
            set title = ?, updated_at = ?
            where id = ? and user_id = ?
            returning *
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, title)
            statement.setInstant(2, updatedAt)
            statement.setObject(3, chatId)
            statement.setString(4, userId)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.toChat() else null
            }
        }
    }

    override suspend fun updateArchived(
        userId: String,
        chatId: UUID,
        archived: Boolean,
        updatedAt: Instant,
    ): Chat? = dataSource.write { connection ->
        connection.prepareStatement(
            """
            update chats
            set archived = ?, updated_at = ?
            where id = ? and user_id = ?
            returning *
            """.trimIndent()
        ).use { statement ->
            statement.setBoolean(1, archived)
            statement.setInstant(2, updatedAt)
            statement.setObject(3, chatId)
            statement.setString(4, userId)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.toChat() else null
            }
        }
    }
}
