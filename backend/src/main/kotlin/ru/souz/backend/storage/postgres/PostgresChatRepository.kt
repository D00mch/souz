package ru.souz.backend.storage.postgres

import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.chat.repository.ChatRepository

class PostgresChatRepository(
    private val dataSource: DataSource,
) : ChatRepository {
    override suspend fun create(chat: Chat): Chat = dataSource.write { connection ->
        connection.prepareStatement(
            """
            insert into chats(id, user_id, title, archived, created_at, updated_at)
            values (?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, chat.id)
            statement.setString(2, chat.userId)
            statement.setString(3, chat.title)
            statement.setBoolean(4, chat.archived)
            statement.setInstant(5, chat.createdAt)
            statement.setInstant(6, chat.updatedAt)
            statement.executeUpdate()
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

    override suspend fun update(chat: Chat): Chat = dataSource.write { connection ->
        connection.prepareStatement(
            """
            update chats
            set title = ?, archived = ?, created_at = ?, updated_at = ?
            where user_id = ? and id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, chat.title)
            statement.setBoolean(2, chat.archived)
            statement.setInstant(3, chat.createdAt)
            statement.setInstant(4, chat.updatedAt)
            statement.setString(5, chat.userId)
            statement.setObject(6, chat.id)
            statement.executeUpdate()
        }
        chat
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
