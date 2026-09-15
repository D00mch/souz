package ru.souz.backend.storage.postgres

import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import ru.souz.backend.vk.VkBotBinding
import ru.souz.backend.vk.VkBotBindingRepository
import ru.souz.backend.vk.VkBotTokenHashConflictException
import ru.souz.backend.vk.VkUserClaimResult

class PostgresVkBotBindingRepository(
    private val dataSource: DataSource,
) : VkBotBindingRepository {
    override suspend fun getByChat(chatId: UUID): VkBotBinding? = dataSource.read { connection ->
        connection.prepareStatement(
            "select * from vk_bot_bindings where chat_id = ?"
        ).use { statement ->
            statement.setObject(1, chatId)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.toVkBotBinding() else null
            }
        }
    }

    override suspend fun getByUserAndChat(
        userId: String,
        chatId: UUID,
    ): VkBotBinding? = dataSource.read { connection ->
        connection.prepareStatement(
            "select * from vk_bot_bindings where user_id = ? and chat_id = ?"
        ).use { statement ->
            statement.setString(1, userId)
            statement.setObject(2, chatId)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.toVkBotBinding() else null
            }
        }
    }

    override suspend fun listForUser(userId: String): List<VkBotBinding> = dataSource.read { connection ->
        connection.prepareStatement(
            "select * from vk_bot_bindings where user_id = ? order by updated_at desc"
        ).use { statement ->
            statement.setString(1, userId)
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(resultSet.toVkBotBinding())
                    }
                }
            }
        }
    }

    override suspend fun findByTokenHash(groupTokenHash: String): VkBotBinding? = dataSource.read { connection ->
        connection.prepareStatement(
            "select * from vk_bot_bindings where group_token_hash = ?"
        ).use { statement ->
            statement.setString(1, groupTokenHash)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.toVkBotBinding() else null
            }
        }
    }

    override suspend fun listEnabled(): List<VkBotBinding> = dataSource.read { connection ->
        connection.prepareStatement(
            """
            select * from vk_bot_bindings
            where enabled = true
            order by updated_at desc
            """.trimIndent()
        ).use { statement ->
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(resultSet.toVkBotBinding())
                    }
                }
            }
        }
    }

    override suspend fun upsertForChat(
        userId: String,
        chatId: UUID,
        groupToken: String,
        groupTokenHash: String,
        linkSecretHash: String,
        vkGroupId: Long,
        vkGroupName: String?,
        now: Instant,
    ): VkBotBinding = try {
        dataSource.write { connection ->
            connection.prepareStatement(
                """
                insert into vk_bot_bindings(
                    id,
                    user_id,
                    chat_id,
                    group_token_encrypted,
                    group_token_hash,
                    link_secret_hash,
                    vk_group_id,
                    vk_group_name,
                    last_ts,
                    enabled,
                    vk_user_id,
                    vk_peer_id,
                    vk_first_name,
                    vk_last_name,
                    linked_at,
                    poller_owner,
                    poller_lease_until,
                    last_error,
                    last_error_at,
                    created_at,
                    updated_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, null, true, null, null, null, null, null, null, null, null, null, ?, ?)
                on conflict (chat_id) do update
                set user_id = excluded.user_id,
                    group_token_encrypted = excluded.group_token_encrypted,
                    group_token_hash = excluded.group_token_hash,
                    link_secret_hash = excluded.link_secret_hash,
                    vk_group_id = excluded.vk_group_id,
                    vk_group_name = excluded.vk_group_name,
                    last_ts = null,
                    enabled = true,
                    vk_user_id = null,
                    vk_peer_id = null,
                    vk_first_name = null,
                    vk_last_name = null,
                    linked_at = null,
                    poller_owner = null,
                    poller_lease_until = null,
                    last_error = null,
                    last_error_at = null,
                    updated_at = excluded.updated_at
                returning *
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setString(2, userId)
                statement.setObject(3, chatId)
                statement.setString(4, groupToken)
                statement.setString(5, groupTokenHash)
                statement.setString(6, linkSecretHash)
                statement.setLong(7, vkGroupId)
                statement.setString(8, vkGroupName)
                statement.setInstant(9, now)
                statement.setInstant(10, now)
                statement.executeQuery().use { resultSet ->
                    resultSet.next()
                    resultSet.toVkBotBinding()
                }
            }
        }
    } catch (e: SQLException) {
        if (e.isConstraintViolation(VK_BOT_BINDINGS_TOKEN_HASH_CONSTRAINT)) {
            throw VkBotTokenHashConflictException()
        }
        throw e
    }

    override suspend fun deleteByChat(chatId: UUID) {
        dataSource.write { connection ->
            connection.prepareStatement(
                "delete from vk_bot_bindings where chat_id = ?"
            ).use { statement ->
                statement.setObject(1, chatId)
                statement.executeUpdate()
            }
        }
    }

    override suspend fun claimVkUser(
        id: UUID,
        linkSecretHash: String,
        vkUserId: Long,
        vkPeerId: Long,
        vkFirstName: String?,
        vkLastName: String?,
        linkedAt: Instant,
        updatedAt: Instant,
    ): VkUserClaimResult = dataSource.write { connection ->
        val current = connection.prepareStatement(
            """
            select * from vk_bot_bindings
            where id = ?
            for update
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, id)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.toVkBotBinding() else null
            }
        } ?: return@write VkUserClaimResult.NotFound

        if (current.linked) {
            return@write VkUserClaimResult.AlreadyLinked(current)
        }
        if (current.linkSecretHash != linkSecretHash) {
            return@write VkUserClaimResult.InvalidSecret(current)
        }

        connection.prepareStatement(
            """
            update vk_bot_bindings
            set link_secret_hash = null,
                vk_user_id = ?,
                vk_peer_id = ?,
                vk_first_name = ?,
                vk_last_name = ?,
                linked_at = ?,
                updated_at = ?
            where id = ?
            returning *
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, vkUserId)
            statement.setLong(2, vkPeerId)
            statement.setString(3, vkFirstName)
            statement.setString(4, vkLastName)
            statement.setInstant(5, linkedAt)
            statement.setInstant(6, updatedAt)
            statement.setObject(7, id)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) {
                    VkUserClaimResult.Claimed(resultSet.toVkBotBinding())
                } else {
                    VkUserClaimResult.NotFound
                }
            }
        }
    }

    override suspend fun tryAcquireLease(
        id: UUID,
        owner: String,
        leaseUntil: Instant,
        now: Instant,
    ): VkBotBinding? = dataSource.write { connection ->
        connection.prepareStatement(
            """
            update vk_bot_bindings
            set poller_owner = ?,
                poller_lease_until = ?
            where id = ?
              and enabled = true
              and (
                poller_lease_until is null
                or poller_lease_until < ?
                or poller_owner = ?
              )
            returning *
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, owner)
            statement.setInstant(2, leaseUntil)
            statement.setObject(3, id)
            statement.setInstant(4, now)
            statement.setString(5, owner)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.toVkBotBinding() else null
            }
        }
    }

    override suspend fun hasActiveLease(
        id: UUID,
        owner: String,
        now: Instant,
    ): Boolean = dataSource.read { connection ->
        connection.prepareStatement(
            """
            select 1
            from vk_bot_bindings
            where id = ?
              and poller_owner = ?
              and poller_lease_until is not null
              and poller_lease_until >= ?
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, id)
            statement.setString(2, owner)
            statement.setInstant(3, now)
            statement.executeQuery().use { resultSet ->
                resultSet.next()
            }
        }
    }

    override suspend fun updateLastTs(
        id: UUID,
        lastTs: String,
        updatedAt: Instant,
        owner: String?,
    ) {
        dataSource.write { connection ->
            connection.prepareStatement(
                """
                update vk_bot_bindings
                set last_ts = ?, updated_at = ?
                where id = ?
                  and (? is null or poller_owner = ?)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, lastTs)
                statement.setInstant(2, updatedAt)
                statement.setObject(3, id)
                statement.setString(4, owner)
                statement.setString(5, owner)
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
                update vk_bot_bindings
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
                update vk_bot_bindings
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
