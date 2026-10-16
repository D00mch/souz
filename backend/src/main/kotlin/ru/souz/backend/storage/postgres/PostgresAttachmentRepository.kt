package ru.souz.backend.storage.postgres

import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import ru.souz.backend.attachments.AttachmentException
import ru.souz.backend.attachments.StoredAttachment

class PostgresAttachmentRepository(private val dataSource: DataSource) {
    suspend fun reserve(
        userId: String, sourceKey: String, name: String, mimeType: String, size: Long, sha256: String, now: Instant,
    ): StoredAttachment = dataSource.write { connection ->
        connection.prepareStatement("""
            insert into stored_attachments(id, user_id, source_key, name, mime_type, size_bytes, sha256, state, created_at)
            values (?, ?, ?, ?, ?, ?, ?, 'pending', ?)
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
            it.executeUpdate()
        }
        connection.prepareStatement("$FILE_SELECT where f.user_id = ? and f.source_key = ?").use {
            it.setString(1, userId)
            it.setString(2, sourceKey)
            it.executeQuery().use { rows ->
                check(rows.next())
                rows.attachment().also { file ->
                    if (file.sha256 != sha256 || file.sizeBytes != size || file.name != name || file.mimeType != mimeType) {
                        throw AttachmentException("attachment_idempotency_conflict")
                    }
                }
            }
        }
    }

    suspend fun complete(file: StoredAttachment, now: Instant): StoredAttachment {
        dataSource.write { connection ->
            connection.prepareStatement("""
                update stored_attachments set state = 'ready', stored_at = ?, expires_at = ?
                where id = ? and user_id = ? and state = 'pending' and created_at > ?
            """.trimIndent()).use {
                it.setInstant(1, now)
                it.setInstant(2, now.plus(Duration.ofDays(30)))
                it.setObject(3, file.id)
                it.setString(4, file.userId)
                it.setInstant(5, now.minus(Duration.ofHours(24)))
                it.executeUpdate()
            }
        }
        return requireNotNull(get(file.userId, file.id))
    }

    suspend fun get(userId: String, id: UUID): StoredAttachment? = dataSource.read { connection ->
        connection.prepareStatement("$FILE_SELECT where f.user_id = ? and f.id = ?").use {
            it.setString(1, userId)
            it.setObject(2, id)
            it.executeQuery().use { rows -> if (rows.next()) rows.attachment() else null }
        }
    }

    suspend fun find(userId: String, sourceKey: String): StoredAttachment? = dataSource.read { connection ->
        connection.prepareStatement("$FILE_SELECT where f.user_id = ? and f.source_key = ? and f.state = 'ready'").use {
            it.setString(1, userId)
            it.setString(2, sourceKey)
            it.executeQuery().use { rows -> if (rows.next()) rows.attachment() else null }
        }
    }

    /** Mark first, delete second; failed deletes remain retryable without granting access. */
    suspend fun expire(now: Instant, limit: Int = 100): List<StoredAttachment> = dataSource.write { connection ->
        connection.prepareStatement("""
            with candidates as (
                select f.id from stored_attachments f where state <> 'deleted' and (
                    state = 'deleting' or expires_at <= ? or
                    (state = 'pending' and created_at <= ?)
                ) order by coalesce(cleanup_attempt_at, created_at), created_at limit ? for update skip locked
            ), changed as (
                update stored_attachments f set state = 'deleting', cleanup_attempt_at = ?
                from candidates c where f.id = c.id returning f.*
            ) select * from changed
        """.trimIndent()).use {
            it.setInstant(1, now)
            it.setInstant(2, now.minus(Duration.ofHours(24)))
            it.setInt(3, limit)
            it.setInstant(4, now)
            it.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.attachment()) } }
        }
    }

    suspend fun deleted(file: StoredAttachment) = dataSource.write { connection ->
        connection.prepareStatement("update stored_attachments set state = 'deleted' where id = ? and state = 'deleting'").use {
            it.setObject(1, file.id)
            it.executeUpdate()
        }
        Unit
    }

    /** Restore cleanup intent when a writer outlives an expired-file cleanup. */
    suspend fun retireLateWrite(file: StoredAttachment) = dataSource.write { connection ->
        connection.prepareStatement("""
            update stored_attachments set state = 'deleting' where id = ? and user_id = ?
                and state in ('deleting', 'deleted')
        """.trimIndent()).use {
            it.setObject(1, file.id)
            it.setString(2, file.userId)
            it.executeUpdate()
        }
        Unit
    }

    private fun ResultSet.attachment() = StoredAttachment(
        id = getObject("id", UUID::class.java), userId = getString("user_id"),
        name = getString("name"), mimeType = getString("mime_type"), sizeBytes = getLong("size_bytes"),
        sha256 = getString("sha256"), state = getString("state"), createdAt = instant("created_at"),
        storedAt = getTimestamp("stored_at")?.toInstant(), expiresAt = getTimestamp("expires_at")?.toInstant(),
    )

    private companion object {
        const val FILE_SELECT = "select f.* from stored_attachments f"
    }
}
