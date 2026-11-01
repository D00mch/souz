package ru.souz.backend.storage.postgres

import com.fasterxml.jackson.module.kotlin.readValue
import ru.souz.backend.client.ClientToolChannel
import ru.souz.backend.client.supportedClientTypes
import ru.souz.backend.client.ClientToolDefinition
import ru.souz.backend.client.DiscoveredClientTools
import ru.souz.llms.restJsonMapper
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.chat.repository.ChatRequestConflictException
import ru.souz.backend.chat.repository.ChatRepository

class PostgresChatRepository(
    private val dataSource: DataSource,
) : ChatRepository {
    override suspend fun create(chat: Chat, tools: List<ClientToolDefinition>): Chat =
        checkNotNull(insert(chat, tools, ignoreDuplicate = false))

    override suspend fun getOrCreate(chat: Chat): Chat =
        insert(chat, emptyList(), ignoreDuplicate = true) ?: get(chat.userId, chat.id)
            ?: throw ChatRequestConflictException(chat.userId, chat.requestId)

    override suspend fun deleteEmptyScheduledChat(userId: String, chatId: UUID): Boolean = dataSource.write { connection ->
        // Admission holds this lock until it commits; the next statement then sees its execution.
        connection.lockChat(userId, chatId)
        connection.prepareStatement(
            """
            delete from chats c
            where c.user_id = ? and c.id = ? and c.client_type = 'scheduled_task'
              and not exists (select 1 from agent_executions where chat_id = c.id)
              and not exists (select 1 from messages where chat_id = c.id)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, userId)
            statement.setObject(2, chatId)
            statement.executeUpdate() == 1
        }
    }

    private suspend fun insert(
        chat: Chat,
        tools: List<ClientToolDefinition>,
        ignoreDuplicate: Boolean,
    ): Chat? = dataSource.write { connection ->
        try {
            connection.prepareStatement(
                """
                insert into chats(
                  id, user_id, client_type, request_id, payload_hash,
                  title, archived, created_at, updated_at, client_tools
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                ${if (ignoreDuplicate) "on conflict do nothing" else ""}
                returning $CHAT_COLUMNS
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
                statement.setString(10, restJsonMapper.writeValueAsString(tools))
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) resultSet.toChat() else null
                }
            }
        } catch (error: java.sql.SQLException) {
            if (error.isConstraintViolation(CHAT_REQUEST_CONSTRAINT)) {
                throw ChatRequestConflictException(chat.userId, chat.requestId)
            }
            throw error
        }
    }

    override suspend fun listClientChannels(userId: String): List<Chat> = dataSource.read { connection ->
        connection.prepareStatement(
            "select $CHAT_COLUMNS from chats where user_id = ? and not archived and client_type = any(?)"
        ).use { statement ->
            statement.setString(1, userId)
            statement.setArray(2, connection.createArrayOf("text", supportedClientTypes.toTypedArray()))
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.toChat()) } }
        }
    }

    override suspend fun clientToolChannels(userId: String): List<ClientToolChannel> = dataSource.read { connection ->
        connection.prepareStatement(
            "select $CHAT_COLUMNS, client_tools, discovered_client_tools from chats " +
                "where user_id = ? and not archived and client_type = any(?)"
        ).use { statement ->
            statement.setString(1, userId)
            statement.setArray(2, connection.createArrayOf("text", supportedClientTypes.toTypedArray()))
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        val chat = result.toChat()
                        val declared = restJsonMapper.readValue<List<ClientToolDefinition>>(result.getString("client_tools"))
                        val discovered = result.getString("discovered_client_tools")?.let {
                            restJsonMapper.readValue<DiscoveredClientTools>(it)
                        }?.takeIf { it.channelId == chat.id }
                        add(ClientToolChannel(chat, (discovered?.tools.orEmpty() + declared).distinctBy { it.name },
                            discoveryComplete = discovered != null))
                    }
                }
            }
        }
    }

    override suspend fun replaceDiscoveredClientTools(userId: String, chatId: UUID, tools: DiscoveredClientTools) {
        require(tools.channelId == chatId) { "Discovery must belong to its supplying channel." }
        dataSource.write { connection ->
            connection.prepareStatement("update chats set discovered_client_tools = ?::jsonb where user_id = ? and id = ? and not archived").use { statement ->
                statement.setString(1, restJsonMapper.writeValueAsString(tools))
                statement.setString(2, userId)
                statement.setObject(3, chatId)
                check(statement.executeUpdate() == 1) { "Discovery conversation is unavailable." }
            }
        }
    }

    override suspend fun get(userId: String, chatId: UUID): Chat? = dataSource.read { connection ->
        connection.prepareStatement(
            "select $CHAT_COLUMNS from chats where user_id = ? and id = ?"
        ).use { statement ->
            statement.setString(1, userId)
            statement.setObject(2, chatId)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.toChat() else null
            }
        }
    }

    override suspend fun getById(chatId: UUID): Chat? = dataSource.read { connection ->
        connection.prepareStatement("select $CHAT_COLUMNS from chats where id = ?").use { statement ->
            statement.setObject(1, chatId)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.toChat() else null
            }
        }
    }

    override suspend fun getByIds(chatIds: List<UUID>): List<Chat> {
        if (chatIds.isEmpty()) return emptyList()
        return dataSource.read { connection ->
            connection.prepareStatement("select $CHAT_COLUMNS from chats where id = any(?)").use { statement ->
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
            "select $CHAT_COLUMNS from chats where user_id = ? and request_id = ?"
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
            select $CHAT_COLUMNS from chats
            where user_id = ?
              and client_type not in ('hook', 'scheduled_task')
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
            touchUpdatedAt(connection, userId, chatId, updatedAt)
        }
    }

    internal fun touchUpdatedAt(connection: java.sql.Connection, userId: String, chatId: UUID, updatedAt: Instant) {
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
            returning $CHAT_COLUMNS
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
            returning $CHAT_COLUMNS
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

private const val CHAT_COLUMNS = "id, user_id, client_type, request_id, payload_hash, title, archived, created_at, updated_at"
