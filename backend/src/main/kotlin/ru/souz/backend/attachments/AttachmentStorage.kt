package ru.souz.backend.attachments

import java.io.InputStream
import java.time.Clock
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import ru.souz.backend.crypto.sha256Hex
import ru.souz.backend.storage.postgres.PostgresAttachmentRepository

class AttachmentStorage(
    private val config: AttachmentStorageConfig,
    private val repository: PostgresAttachmentRepository,
    private val objects: S3AttachmentObjects,
    private val clock: Clock,
) {
    // Bounds total in-memory file bytes across concurrent uploads; each save() holds one file fully buffered.
    private val uploadSlots = Semaphore(config.maxConcurrentUploads)

    suspend fun save(userId: String, sourceKey: String, name: String, input: InputStream): StoredAttachment =
        uploadSlots.withPermit { saveWithinLimit(userId, sourceKey, name, input) }

    private suspend fun saveWithinLimit(userId: String, sourceKey: String, name: String, input: InputStream): StoredAttachment {
        require(userId.isNotBlank() && sourceKey.isNotBlank() && sourceKey.length <= 512)
        val bytes = runInterruptible(Dispatchers.IO) { input.use { it.readAttachmentBytes(config.maxFileBytes) } }
        val safeName = attachmentName(name)
        val mime = attachmentMime(bytes, safeName)
        val file = repository.reserve(userId, sourceKey, safeName, mime, bytes.size.toLong(), bytes.sha256Hex(), clock.instant())
        if (file.state == "ready") return available(file)
        if (file.state != "pending" || !file.createdAt.plus(Duration.ofHours(24)).isAfter(clock.instant())) {
            throw AttachmentException("attachment_expired")
        }
        currentCoroutineContext().ensureActive()
        var committed = false
        try {
            objects.put(file, bytes)
            val completed = repository.complete(file, clock.instant())
            if (completed.state != "ready") {
                repository.retireLateWrite(file)
                objects.delete(file)
                throw AttachmentException("attachment_expired")
            }
            return get(userId, completed.id).also { committed = true }
        } finally {
            // A cancelled/late writer may finish after cleanup has already left a tombstone.
            // Keep that object retryable even if its user was removed before complete().
            if (!committed) withContext(NonCancellable) {
                try { repository.retireLateWrite(file) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { logger.warn("Late attachment write cleanup scheduling failed for file {}", file.id) }
            }
        }
    }

    suspend fun get(userId: String, id: UUID): StoredAttachment {
        return available(repository.get(userId, id) ?: throw AttachmentException("attachment_not_found"))
    }

    suspend fun find(userId: String, sourceKey: String): StoredAttachment? {
        return repository.find(userId, sourceKey)?.let(::available)
    }

    suspend fun read(userId: String, id: UUID): ByteArray {
        val file = get(userId, id)
        val bytes = objects.read(file, config.maxFileBytes)
        available(file)
        return bytes
    }

    suspend fun cleanup(): Int {
        var removed = 0
        for (file in repository.expire(clock.instant())) {
            try {
                objects.delete(file)
                repository.deleted(file)
                removed++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Never log S3 exception messages: they can carry signed request details.
                logger.warn("Attachment cleanup failed for file {}", file.id)
            }
        }
        return removed
    }

    private fun available(file: StoredAttachment): StoredAttachment {
        if (file.state in setOf("deleting", "deleted") || file.expiresAt?.isAfter(clock.instant()) == false) {
            throw AttachmentException("attachment_expired")
        }
        if (file.state != "ready") throw AttachmentException("attachment_not_ready")
        return file
    }

    private companion object {
        val logger = LoggerFactory.getLogger(AttachmentStorage::class.java)
    }
}
