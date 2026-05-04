package ru.souz.backend.storage.postgres

import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import ru.souz.backend.telegram.TelegramBotBinding
import ru.souz.backend.telegram.TelegramBotBindingRepository
import ru.souz.backend.telegram.TelegramBotTokenHashConflictException

class PostgresTelegramBotBindingRepository(
    private val dataSource: DataSource,
) : TelegramBotBindingRepository {
    override suspend fun getByChat(chatId: UUID): TelegramBotBinding? = dataSource.read { connection ->
        connection.prepareStatement(
            "select * from telegram_bot_bindings where chat_id = ?"
        ).use { statement ->
            statement.setObject(1, chatId)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.toTelegramBotBinding() else null
            }
        }
    }

    override suspend fun getByUserAndChat(
        userId: String,
        chatId: UUID,
    ): TelegramBotBinding? = dataSource.read { connection ->
        connection.prepareStatement(
            "select * from telegram_bot_bindings where user_id = ? and chat_id = ?"
        ).use { statement ->
            statement.setString(1, userId)
            statement.setObject(2, chatId)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.toTelegramBotBinding() else null
            }
        }
    }

    override suspend fun findByTokenHash(botTokenHash: String): TelegramBotBinding? = dataSource.read { connection ->
        connection.prepareStatement(
            "select * from telegram_bot_bindings where bot_token_hash = ?"
        ).use { statement ->
            statement.setString(1, botTokenHash)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.toTelegramBotBinding() else null
            }
        }
    }

    override suspend fun listEnabled(): List<TelegramBotBinding> = dataSource.read { connection ->
        connection.prepareStatement(
            """
            select * from telegram_bot_bindings
            where enabled = true
            order by updated_at desc
            """.trimIndent()
        ).use { statement ->
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(resultSet.toTelegramBotBinding())
                    }
                }
            }
        }
    }

    override suspend fun upsertForChat(
        userId: String,
        chatId: UUID,
        botToken: String,
        botTokenHash: String,
        now: Instant,
    ): TelegramBotBinding = try {
        dataSource.write { connection ->
            connection.prepareStatement(
                """
                insert into telegram_bot_bindings(
                    id,
                    user_id,
                    chat_id,
                    bot_token,
                    bot_token_hash,
                    last_update_id,
                    enabled,
                    last_error,
                    last_error_at,
                    created_at,
                    updated_at
                )
                values (?, ?, ?, ?, ?, 0, true, null, null, ?, ?)
                on conflict (chat_id) do update
                set user_id = excluded.user_id,
                    bot_token = excluded.bot_token,
                    bot_token_hash = excluded.bot_token_hash,
                    last_update_id = 0,
                    enabled = true,
                    last_error = null,
                    last_error_at = null,
                    updated_at = excluded.updated_at
                returning *
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setString(2, userId)
                statement.setObject(3, chatId)
                statement.setString(4, botToken)
                statement.setString(5, botTokenHash)
                statement.setInstant(6, now)
                statement.setInstant(7, now)
                statement.executeQuery().use { resultSet ->
                    resultSet.next()
                    resultSet.toTelegramBotBinding()
                }
            }
        }
    } catch (e: SQLException) {
        if (e.isConstraintViolation(TELEGRAM_BOT_BINDINGS_TOKEN_HASH_CONSTRAINT)) {
            throw TelegramBotTokenHashConflictException()
        }
        throw e
    }

    override suspend fun deleteByChat(chatId: UUID) {
        dataSource.write { connection ->
            connection.prepareStatement(
                "delete from telegram_bot_bindings where chat_id = ?"
            ).use { statement ->
                statement.setObject(1, chatId)
                statement.executeUpdate()
            }
        }
    }

    override suspend fun updateLastUpdateId(
        id: UUID,
        lastUpdateId: Long,
        updatedAt: Instant,
    ) {
        dataSource.write { connection ->
            connection.prepareStatement(
                """
                update telegram_bot_bindings
                set last_update_id = ?, updated_at = ?
                where id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, lastUpdateId)
                statement.setInstant(2, updatedAt)
                statement.setObject(3, id)
                statement.executeUpdate()
            }
        }
    }

    override suspend fun markError(
        id: UUID,
        lastError: String,
        lastErrorAt: Instant,
        disable: Boolean,
    ) {
        dataSource.write { connection ->
            connection.prepareStatement(
                """
                update telegram_bot_bindings
                set enabled = case when ? then false else enabled end,
                    last_error = ?,
                    last_error_at = ?,
                    updated_at = ?
                where id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setBoolean(1, disable)
                statement.setString(2, lastError)
                statement.setInstant(3, lastErrorAt)
                statement.setInstant(4, lastErrorAt)
                statement.setObject(5, id)
                statement.executeUpdate()
            }
        }
    }

    override suspend fun clearError(
        id: UUID,
        updatedAt: Instant,
    ) {
        dataSource.write { connection ->
            connection.prepareStatement(
                """
                update telegram_bot_bindings
                set last_error = null,
                    last_error_at = null,
                    updated_at = ?
                where id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setInstant(1, updatedAt)
                statement.setObject(2, id)
                statement.executeUpdate()
            }
        }
    }
}
