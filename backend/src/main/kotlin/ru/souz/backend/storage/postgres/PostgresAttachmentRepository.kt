package ru.souz.backend.storage.postgres

import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import ru.souz.backend.attachments.ATTACHMENT_CLEANUP_LEASE
import ru.souz.backend.attachments.ATTACHMENT_EXECUTION_SAVE_LIMIT
import ru.souz.backend.attachments.ATTACHMENT_OWNER_SAVE_LIMIT
import ru.souz.backend.attachments.ATTACHMENT_PENDING_LIFETIME
import ru.souz.backend.attachments.ATTACHMENT_RETENTION
import ru.souz.backend.attachments.AttachmentException
import ru.souz.backend.attachments.AttachmentSaveBudget
import ru.souz.backend.attachments.StoredAttachment
import ru.souz.backend.attachments.attachmentObjectKey
import ru.souz.backend.metrics.BackendMetrics

class PostgresAttachmentRepository(
    private val dataSource: DataSource,
    private val saveBudget: AttachmentSaveBudget = AttachmentSaveBudget(),
    private val metrics: BackendMetrics? = null,
) {
    /** Insert ready metadata only after the caller has verified the already-uploaded object. */
    suspend fun registerUpload(file: StoredAttachment): StoredAttachment = dataSource.write(networkTimeoutMillis = 15_000) { connection ->
        connection.attachmentStatement("""
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
        connection.attachmentStatement("select * from stored_attachments where object_key = ? and user_id = ?").use {
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
        executionId: UUID? = null,
    ): StoredAttachment = try {
        dataSource.write(networkTimeoutMillis = 15_000, afterCommit = { file ->
            if (executionId != null && file.state == "pending") metrics?.attachmentSaveAdmitted(file.sizeBytes)
        }) { connection ->
            val id = UUID.randomUUID()
            val pending = StoredAttachment(
                id, userId, name, mimeType, size, sha256, "pending", now, null, null,
                now.plus(ATTACHMENT_PENDING_LIFETIME), attachmentObjectKey(userId, id),
            )
            if (executionId == null) return@write connection.reserve(pending, sourceKey)

            connection.lockOwnerSaveBudget(userId)
            connection.findReservation(pending, sourceKey)?.let { return@write it }
            val admittedAt = connection.checkSaveBudget(userId, executionId, size)
            val file = connection.reserve(pending, sourceKey)
            if (file.state == "pending") connection.recordSaveCharge(file, executionId, admittedAt)
            file
        }
    } catch (error: AttachmentException) {
        when (error.code) {
            ATTACHMENT_EXECUTION_SAVE_LIMIT -> metrics?.attachmentSaveRejected("execution")
            ATTACHMENT_OWNER_SAVE_LIMIT -> metrics?.attachmentSaveRejected("owner")
        }
        throw error
    }

    private fun Connection.reserve(pending: StoredAttachment, sourceKey: String): StoredAttachment =
        attachmentStatement("""
            insert into stored_attachments(id, user_id, source_key, name, mime_type, size_bytes, sha256, state, created_at, cleanup_due_at, object_key)
            values (?, ?, ?, ?, ?, ?, ?, 'pending', ?, ?, ?)
            on conflict (user_id, source_key) do nothing
            returning *
        """.trimIndent()).use {
            it.setObject(1, pending.id)
            it.setString(2, pending.userId)
            it.setString(3, sourceKey)
            it.setString(4, pending.name)
            it.setString(5, pending.mimeType)
            it.setLong(6, pending.sizeBytes)
            it.setString(7, pending.sha256)
            it.setInstant(8, pending.createdAt)
            it.setInstant(9, pending.cleanupDueAt)
            it.setString(10, pending.objectKey)
            it.executeQuery().use { rows -> if (rows.next()) rows.attachment() else null }
        } ?: findReservation(pending, sourceKey) ?: throw AttachmentException("attachment_not_found")

    private fun Connection.findReservation(pending: StoredAttachment, sourceKey: String): StoredAttachment? =
        attachmentStatement("select * from stored_attachments where user_id = ? and source_key = ?").use {
            it.setString(1, pending.userId)
            it.setString(2, sourceKey)
            it.executeQuery().use { rows ->
                if (!rows.next()) return@use null
                rows.attachment().also { file ->
                    if (file.state == "deleting") throw AttachmentException("attachment_expired")
                    if (file.state == "pending") throw AttachmentException("attachment_not_ready")
                    if (file.sha256 != pending.sha256 || file.sizeBytes != pending.sizeBytes ||
                        file.name != pending.name || file.mimeType != pending.mimeType) {
                        throw AttachmentException("attachment_idempotency_conflict")
                    }
                }
            }
        }

    private fun Connection.lockOwnerSaveBudget(userId: String) {
        // Read charges with a fresh snapshot after waiting for the previous admission to commit.
        attachmentStatement("set transaction isolation level read committed").use { it.execute() }
        attachmentStatement("select pg_advisory_xact_lock(hashtextextended(?, 0))").use {
            it.setString(1, "attachment-save-budget:$userId")
            it.execute()
        }
    }

    private fun Connection.checkSaveBudget(userId: String, executionId: UUID, size: Long): Instant {
        attachmentStatement("""
            select save_count, size_bytes from attachment_execution_save_totals where execution_id = ?
        """.trimIndent()).use {
            it.setObject(1, executionId)
            it.executeQuery().use { rows ->
                val hasTotals = rows.next()
                val saves = if (hasTotals) rows.getInt(1) else 0
                val bytes = if (hasTotals) rows.getLong(2) else 0L
                if (saves >= saveBudget.maxExecutionSaves || size > saveBudget.maxExecutionSaveBytes - bytes) {
                    throw AttachmentException(ATTACHMENT_EXECUTION_SAVE_LIMIT)
                }
            }
        }
        return attachmentStatement("""
            select count(*), coalesce(sum(size_bytes), 0), statement_timestamp() as admitted_at
            from attachment_save_charges
            where user_id = ? and admitted_at > statement_timestamp() - interval '24 hours'
        """.trimIndent()).use {
            it.setString(1, userId)
            it.executeQuery().use { rows ->
                check(rows.next())
                if (rows.getLong(1) >= saveBudget.maxOwnerSavesPer24h || size > saveBudget.maxOwnerSaveBytesPer24h - rows.getLong(2)) {
                    throw AttachmentException(ATTACHMENT_OWNER_SAVE_LIMIT)
                }
                rows.instant("admitted_at")
            }
        }
    }

    private fun Connection.recordSaveCharge(file: StoredAttachment, executionId: UUID, admittedAt: Instant) {
        attachmentStatement("""
            insert into attachment_execution_save_totals(execution_id, save_count, size_bytes)
            select id, 1, ? from agent_executions where id = ? and user_id = ?
            on conflict (execution_id) do update set
                save_count = attachment_execution_save_totals.save_count + 1,
                size_bytes = attachment_execution_save_totals.size_bytes + excluded.size_bytes
        """.trimIndent()).use {
            it.setLong(1, file.sizeBytes)
            it.setObject(2, executionId)
            it.setString(3, file.userId)
            if (it.executeUpdate() != 1) throw AttachmentException("attachment_execution_required")
        }
        attachmentStatement("""
            insert into attachment_save_charges(id, user_id, size_bytes, admitted_at) values (?, ?, ?, ?)
        """.trimIndent()).use {
            it.setObject(1, file.id)
            it.setString(2, file.userId)
            it.setLong(3, file.sizeBytes)
            it.setInstant(4, admittedAt)
            it.executeUpdate()
        }
    }

    internal suspend fun pruneSaveCharges(): Unit = dataSource.write(networkTimeoutMillis = 15_000) { connection ->
        connection.attachmentStatement("""
            delete from attachment_save_charges where id in (
                select id from attachment_save_charges
                where admitted_at <= statement_timestamp() - interval '24 hours'
                order by admitted_at, id limit 1000 for update skip locked
            )
        """.trimIndent()).use { it.executeUpdate() }
    }

    suspend fun complete(file: StoredAttachment, now: Instant): StoredAttachment = dataSource.write(networkTimeoutMillis = 15_000) { connection ->
        connection.attachmentStatement("""
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

    internal suspend fun getAll(userId: String, ids: Set<UUID>): Map<UUID, StoredAttachment> = dataSource.read(networkTimeoutMillis = 15_000) { connection ->
        connection.attachmentStatement("select * from stored_attachments where user_id = ? and id = any(?)").use { statement ->
            statement.setString(1, userId)
            statement.setArray(2, connection.createArrayOf("uuid", ids.toTypedArray()))
            statement.executeQuery().use { rows ->
                buildMap { while (rows.next()) rows.attachment().let { put(it.id, it) } }
            }
        }
    }

    /** Check ownership before an import can read an object already registered in the bucket. */
    suspend fun getByObjectKey(userId: String, objectKey: String): StoredAttachment? = dataSource.read(networkTimeoutMillis = 15_000) { connection ->
        connection.attachmentStatement("select * from stored_attachments where object_key = ?").use {
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

    private suspend fun lookup(userId: String, column: String, value: Any): StoredAttachment? = dataSource.read(networkTimeoutMillis = 15_000) { connection ->
        connection.attachmentStatement("select * from stored_attachments where user_id = ? and $column = ?").use {
            it.setString(1, userId)
            it.setObject(2, value)
            it.executeQuery().use { rows -> if (rows.next()) rows.attachment() else null }
        }
    }

    /**
     * Leases one due row and commits its access revocation before S3 deletion. Each claim moves the deadline
     * forward, so other workers retry the row only after the lease, and [remove] accepts only the latest claim.
     */
    suspend fun claim(now: Instant): StoredAttachment? = dataSource.write(networkTimeoutMillis = 15_000) { connection ->
        connection.attachmentStatement("""
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

    suspend fun remove(file: StoredAttachment): Unit = dataSource.write(networkTimeoutMillis = 15_000) { connection ->
        connection.attachmentStatement("delete from stored_attachments where id = ? and state = 'deleting' and cleanup_due_at = ?").use {
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

private fun Connection.attachmentStatement(sql: String) = prepareStatement(sql).apply { queryTimeout = 10 }
