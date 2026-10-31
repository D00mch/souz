package ru.souz.backend.storage.postgres

import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import ru.souz.backend.attachments.ATTACHMENT_CLEANUP_LEASE
import ru.souz.backend.attachments.ATTACHMENT_PENDING_LIFETIME
import ru.souz.backend.attachments.ATTACHMENT_RETENTION
import ru.souz.backend.attachments.AttachmentException
import ru.souz.backend.attachments.StoredAttachment
import ru.souz.backend.attachments.attachmentObjectKey

class PostgresAttachmentRepository(private val dataSource: DataSource) {
    /** Insert ready metadata only after the caller has verified the already-uploaded object. */
    suspend fun registerUpload(file: StoredAttachment): StoredAttachment = dataSource.write { connection ->
        connection.prepareStatement("""
            insert into stored_attachments(id, user_id, source_key, name, mime_type, size_bytes, sha256,
                state, created_at, stored_at, expires_at, cleanup_due_at, object_key)
            values (?, ?, ?, ?, ?, ?, ?, 'ready', ?, ?, ?, ?, ?)
            on conflict (object_key) do nothing
        """.trimIndent()).use {
            it.setObject(1, file.id)
            it.setString(2, file.userId)
            it.setString(3, "s3:${file.id}")
            it.setString(4, file.name)
            it.setString(5, file.mimeType)
            it.setLong(6, file.sizeBytes)
            it.setString(7, file.sha256)
            it.setInstant(8, file.createdAt)
            it.setInstant(9, requireNotNull(file.storedAt))
            it.setInstant(10, requireNotNull(file.expiresAt))
            it.setInstant(11, file.cleanupDueAt)
            it.setString(12, file.objectKey)
            it.executeUpdate()
        }
        connection.prepareStatement("select * from stored_attachments where object_key = ? and user_id = ?").use {
            it.setString(1, file.objectKey)
            it.setString(2, file.userId)
            it.executeQuery().use { rows ->
                if (!rows.next()) throw AttachmentException("attachment_not_found")
                rows.attachment().also { existing ->
                    if (existing.name != file.name || existing.mimeType != file.mimeType ||
                        existing.sizeBytes != file.sizeBytes || existing.sha256 != file.sha256) {
                        throw AttachmentException("attachment_idempotency_conflict")
                    }
                }
            }
        }
    }

    suspend fun reserve(
        userId: String, sourceKey: String, name: String, mimeType: String, size: Long, sha256: String, now: Instant,
    ): StoredAttachment = dataSource.write { connection ->
        val id = UUID.randomUUID()
        val inserted = connection.prepareStatement("""
            insert into stored_attachments(id, user_id, source_key, name, mime_type, size_bytes, sha256, state, created_at, cleanup_due_at, object_key)
            values (?, ?, ?, ?, ?, ?, ?, 'pending', ?, ?, ?)
            on conflict (user_id, source_key) do nothing
        """.trimIndent()).use {
            it.setObject(1, id)
            it.setString(2, userId)
            it.setString(3, sourceKey)
            it.setString(4, name)
            it.setString(5, mimeType)
            it.setLong(6, size)
            it.setString(7, sha256)
            it.setInstant(8, now)
            it.setInstant(9, now.plus(ATTACHMENT_PENDING_LIFETIME))
            it.setString(10, attachmentObjectKey(userId, id))
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

    /** Check ownership before an import can read an object already registered in the bucket. */
    suspend fun getByObjectKey(userId: String, objectKey: String): StoredAttachment? = dataSource.read { connection ->
        connection.prepareStatement("select * from stored_attachments where object_key = ?").use {
            it.setString(1, objectKey)
            it.executeQuery().use { rows ->
                if (!rows.next()) null else rows.attachment().also { file ->
                    if (file.userId != userId) throw AttachmentException("attachment_not_found")
                }
            }
        }
    }

    suspend fun find(userId: String, sourceKey: String): StoredAttachment? =
        lookup(userId, "source_key", sourceKey)?.takeIf { it.state == "ready" }

    private suspend fun lookup(userId: String, column: String, value: Any): StoredAttachment? = dataSource.read { connection ->
        connection.prepareStatement("select * from stored_attachments where user_id = ? and $column = ?").use {
            it.setString(1, userId)
            it.setObject(2, value)
            it.executeQuery().use { rows -> if (rows.next()) rows.attachment() else null }
        }
    }

    /**
     * Leases one due row and commits its access revocation before S3 deletion. Each claim moves the deadline
     * forward, so other workers retry the row only after the lease, and [remove] accepts only the latest claim.
     */
    suspend fun claim(now: Instant): StoredAttachment? = dataSource.write { connection ->
        connection.prepareStatement("""
            update stored_attachments f set state = 'deleting', cleanup_due_at = ?
            from (
                select id from stored_attachments where cleanup_due_at <= ?
                order by cleanup_due_at, id limit 1 for update skip locked
            ) due where f.id = due.id returning f.*
        """.trimIndent()).use {
            it.setInstant(1, now.plus(ATTACHMENT_CLEANUP_LEASE))
            it.setInstant(2, now)
            it.executeQuery().use { rows -> if (rows.next()) rows.attachment() else null }
        }
    }

    suspend fun remove(file: StoredAttachment): Unit = dataSource.write { connection ->
        connection.prepareStatement("delete from stored_attachments where id = ? and state = 'deleting' and cleanup_due_at = ?").use {
            it.setObject(1, file.id)
            it.setInstant(2, file.cleanupDueAt)
            it.executeUpdate()
        }
    }

    private fun ResultSet.attachment() = StoredAttachment(
        id = getObject("id", UUID::class.java), userId = getString("user_id"),
        name = getString("name"), mimeType = getString("mime_type"), sizeBytes = getLong("size_bytes"),
        sha256 = getString("sha256"), state = getString("state"), createdAt = instant("created_at"),
        storedAt = getTimestamp("stored_at")?.toInstant(), expiresAt = getTimestamp("expires_at")?.toInstant(),
        cleanupDueAt = instant("cleanup_due_at"), objectKey = getString("object_key"),
    )
}
