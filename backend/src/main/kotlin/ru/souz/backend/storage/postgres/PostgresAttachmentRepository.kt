package ru.souz.backend.storage.postgres

import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import ru.souz.backend.attachments.ATTACHMENT_CLEANUP_BATCH_SIZE
import ru.souz.backend.attachments.ATTACHMENT_PENDING_LIFETIME
import ru.souz.backend.attachments.ATTACHMENT_RETENTION
import ru.souz.backend.attachments.AttachmentException
import ru.souz.backend.attachments.StoredAttachment

class PostgresAttachmentRepository(private val dataSource: DataSource) {
    suspend fun reserve(
        userId: String, sourceKey: String, name: String, mimeType: String, size: Long, sha256: String, now: Instant,
    ): StoredAttachment = dataSource.write { connection ->
        val inserted = connection.prepareStatement("""
            insert into stored_attachments(id, user_id, source_key, name, mime_type, size_bytes, sha256, state, created_at, cleanup_due_at)
            values (?, ?, ?, ?, ?, ?, ?, 'pending', ?, ?)
            on conflict (user_id, source_key) do nothing
        """.trimIndent()).use {
            it.setObject(1, UUID.randomUUID())
            it.setString(2, userId)
            it.setString(3, sourceKey)
            it.setString(4, name)
            it.setString(5, mimeType)
            it.setLong(6, size)
            it.setString(7, sha256)
            it.setInstant(8, now)
            it.setInstant(9, now.plus(ATTACHMENT_PENDING_LIFETIME))
            it.executeUpdate() == 1
        }
        connection.prepareStatement("select * from stored_attachments where user_id = ? and source_key = ?").use {
            it.setString(1, userId)
            it.setString(2, sourceKey)
            it.executeQuery().use { rows ->
                if (!rows.next()) throw AttachmentException("attachment_not_found")
                rows.attachment().also { file ->
                    if (file.state == "deleting") throw AttachmentException("attachment_expired")
                    if (!inserted && file.state == "pending") throw AttachmentException("attachment_not_ready")
                    if (file.sha256 != sha256 || file.sizeBytes != size || file.name != name || file.mimeType != mimeType) {
                        throw AttachmentException("attachment_idempotency_conflict")
                    }
                }
            }
        }
    }

    suspend fun complete(file: StoredAttachment, now: Instant): StoredAttachment = dataSource.write { connection ->
        connection.prepareStatement("""
            update stored_attachments set state = 'ready', stored_at = ?, expires_at = ?, cleanup_due_at = ?
            where id = ? and user_id = ? and state = 'pending' and cleanup_due_at > ?
            returning *
        """.trimIndent()).use {
            it.setInstant(1, now)
            it.setInstant(2, now.plus(ATTACHMENT_RETENTION))
            it.setInstant(3, now.plus(ATTACHMENT_RETENTION))
            it.setObject(4, file.id)
            it.setString(5, file.userId)
            it.setInstant(6, now)
            it.executeQuery().use { rows ->
                if (!rows.next()) throw AttachmentException("attachment_expired")
                rows.attachment()
            }
        }
    }

    suspend fun get(userId: String, id: UUID): StoredAttachment? = lookup(userId, "id", id)

    suspend fun find(userId: String, sourceKey: String): StoredAttachment? =
        lookup(userId, "source_key", sourceKey)?.takeIf { it.state == "ready" }

    private suspend fun lookup(userId: String, column: String, value: Any): StoredAttachment? = dataSource.read { connection ->
        connection.prepareStatement("select * from stored_attachments where user_id = ? and $column = ?").use {
            it.setString(1, userId)
            it.setObject(2, value)
            it.executeQuery().use { rows -> if (rows.next()) rows.attachment() else null }
        }
    }

    /** Commit the access revocation before touching S3; failed deletions remain eligible. */
    suspend fun expire(now: Instant, limit: Int = ATTACHMENT_CLEANUP_BATCH_SIZE): List<StoredAttachment> = dataSource.write { connection ->
        connection.prepareStatement("""
            update stored_attachments f set state = 'deleting', cleanup_due_at = ?
            from (
                select id from stored_attachments where cleanup_due_at <= ?
                order by cleanup_due_at, id limit ? for update skip locked
            ) due where f.id = due.id returning f.*
        """.trimIndent()).use {
            it.setInstant(1, now)
            it.setInstant(2, now)
            it.setInt(3, limit)
            it.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.attachment()) } }
        }
    }

    suspend fun remove(file: StoredAttachment): Unit = dataSource.write { connection ->
        connection.prepareStatement("delete from stored_attachments where id = ? and state = 'deleting'").use {
            it.setObject(1, file.id)
            it.executeUpdate()
        }
    }

    private fun ResultSet.attachment() = StoredAttachment(
        id = getObject("id", UUID::class.java), userId = getString("user_id"),
        name = getString("name"), mimeType = getString("mime_type"), sizeBytes = getLong("size_bytes"),
        sha256 = getString("sha256"), state = getString("state"), createdAt = instant("created_at"),
        storedAt = getTimestamp("stored_at")?.toInstant(), expiresAt = getTimestamp("expires_at")?.toInstant(),
    )
}
