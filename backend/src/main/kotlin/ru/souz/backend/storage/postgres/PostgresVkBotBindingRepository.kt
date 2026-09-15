package ru.souz.backend.storage.postgres

import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import ru.souz.backend.vk.VkBotBinding

class PostgresVkBotBindingRepository(private val dataSource: DataSource) {
    suspend fun getByChat(chatId: UUID): VkBotBinding? =
        query("select * from vk_bot_bindings where chat_id = ?", chatId).firstOrNull()

    suspend fun getByUserAndChat(userId: String, chatId: UUID): VkBotBinding? =
        getByChat(chatId)?.takeIf { it.userId == userId }

    suspend fun listForUser(userId: String): List<VkBotBinding> =
        query("select * from vk_bot_bindings where user_id = ? order by updated_at desc", userId)

    suspend fun listEnabled(): List<VkBotBinding> =
        query("select * from vk_bot_bindings where enabled = true order by updated_at desc")

    suspend fun upsertForChat(
        userId: String,
        chatId: UUID,
        groupToken: String,
        groupTokenHash: String,
        linkSecretHash: String,
        vkGroupId: Long,
        vkGroupName: String?,
        now: Instant,
    ): VkBotBinding = try {
        query(
            """
            insert into vk_bot_bindings(
                id, user_id, chat_id, group_token_encrypted, group_token_hash,
                link_secret_hash, vk_group_id, vk_group_name, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (chat_id) do update set
                id = excluded.id,
                group_token_encrypted = excluded.group_token_encrypted,
                group_token_hash = excluded.group_token_hash,
                link_secret_hash = excluded.link_secret_hash,
                vk_group_id = excluded.vk_group_id,
                vk_group_name = excluded.vk_group_name,
                last_ts = null, enabled = true,
                vk_user_id = null, vk_peer_id = null,
                vk_first_name = null, vk_last_name = null, linked_at = null, linked_message_id = null,
                poller_owner = null, poller_lease_until = null,
                last_error = null, last_error_at = null,
                updated_at = excluded.updated_at
            returning *
            """,
            UUID.randomUUID(), userId, chatId, groupToken, groupTokenHash,
            linkSecretHash, vkGroupId, vkGroupName, now, now,
        ).single()
    } catch (e: SQLException) {
        if (e.isConstraintViolation(VK_BOT_BINDINGS_TOKEN_HASH_CONSTRAINT) ||
            e.isConstraintViolation("vk_bot_bindings_vk_group_id_key")
        ) throw VkBotTokenHashConflictException()
        throw e
    }

    suspend fun deleteByChat(chatId: UUID) {
        query("delete from vk_bot_bindings where chat_id = ?", chatId)
    }

    suspend fun claimVkUser(
        id: UUID,
        owner: String,
        linkSecretHash: String,
        messageId: Long,
        vkUserId: Long,
        vkPeerId: Long,
        vkFirstName: String?,
        vkLastName: String?,
        now: Instant,
    ): VkBotBinding? = query(
        """
        update vk_bot_bindings set link_secret_hash = null,
            vk_user_id = ?, vk_peer_id = ?, vk_first_name = ?, vk_last_name = ?,
            linked_at = ?, updated_at = ?, linked_message_id = ?
        where id = ? and enabled and poller_owner = ? and poller_lease_until >= ?
            and link_secret_hash = ? and vk_user_id is null and vk_peer_id is null
        returning *
        """,
        vkUserId, vkPeerId, vkFirstName, vkLastName, now, now, messageId, id, owner, now, linkSecretHash,
    ).firstOrNull()

    suspend fun tryAcquireLease(id: UUID, owner: String, leaseUntil: Instant, now: Instant): VkBotBinding? = query(
        """
        update vk_bot_bindings set poller_owner = ?, poller_lease_until = ?
        where id = ? and enabled
            and (poller_lease_until is null or poller_lease_until < ? or poller_owner = ?)
        returning *
        """,
        owner, leaseUntil, id, now, owner,
    ).firstOrNull()

    suspend fun renewLease(id: UUID, owner: String, leaseUntil: Instant, now: Instant): Boolean = query(
        """
        update vk_bot_bindings set poller_lease_until = ?
        where id = ? and enabled and poller_owner = ? and poller_lease_until >= ? returning *
        """,
        leaseUntil, id, owner, now,
    ).isNotEmpty()

    suspend fun hasActiveLease(id: UUID, owner: String, now: Instant): Boolean = query(
        "select * from vk_bot_bindings where id = ? and enabled and poller_owner = ? and poller_lease_until >= ?",
        id, owner, now,
    ).isNotEmpty()

    suspend fun updateLastTs(id: UUID, owner: String, lastTs: String, now: Instant) {
        query(
            """
            update vk_bot_bindings set last_ts = ?, last_error = null, last_error_at = null, updated_at = ?
            where id = ? and enabled and poller_owner = ? and poller_lease_until >= ?
            """,
            lastTs, now, id, owner, now,
        )
    }

    suspend fun markError(id: UUID, owner: String, error: String, now: Instant, disable: Boolean = false) {
        query(
            """
            update vk_bot_bindings set last_error = ?, last_error_at = ?, updated_at = ?, enabled = not ?
            where id = ? and enabled and poller_owner = ? and poller_lease_until >= ?
            """,
            error, now, now, disable, id, owner, now,
        )
    }

    // Every mutation is a single atomic statement; bind and map rows in one place.
    private suspend fun query(sql: String, vararg values: Any?): List<VkBotBinding> = dataSource.read { connection ->
        connection.prepareStatement(sql.trimIndent()).use { statement ->
            values.forEachIndexed { index, value ->
                if (value is Instant) statement.setInstant(index + 1, value)
                else statement.setObject(index + 1, value)
            }
            if (!statement.execute()) emptyList() else statement.resultSet.use { rows ->
                buildList { while (rows.next()) add(rows.toVkBotBinding()) }
            }
        }
    }
}

class VkBotTokenHashConflictException : RuntimeException("VK community is already bound.")
