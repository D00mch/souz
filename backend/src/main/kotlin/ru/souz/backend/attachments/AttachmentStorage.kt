package ru.souz.backend.attachments

import java.io.InputStream
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import ru.souz.backend.crypto.sha256Hex
import ru.souz.backend.storage.postgres.PostgresAttachmentRepository
import software.amazon.awssdk.services.s3.model.S3Exception

class AttachmentStorage(
    private val config: AttachmentStorageConfig,
    private val repository: PostgresAttachmentRepository,
    private val objects: S3AttachmentObjects,
    private val clock: Clock,
) {
    // Limits active transfers, not total heap: callers can retain returned arrays.
    private val transfers = Semaphore(config.maxConcurrentUploads)

    suspend fun save(userId: String, sourceKey: String, name: String, input: InputStream): StoredAttachment = transfers.withPermit {
        require(userId.isNotBlank() && sourceKey.isNotBlank() && sourceKey.length <= 512)
        val bytes = runInterruptible(Dispatchers.IO) { input.use { it.readAttachmentBytes(config.maxFileBytes) } }
        val safeName = attachmentName(name)
        val file = repository.reserve(userId, sourceKey, safeName, attachmentMime(bytes, safeName),
            bytes.size.toLong(), bytes.sha256Hex(), clock.instant())
        if (file.state == "ready") return@withPermit available(file)
        // Failed/canceled uploads stay pending until their cleanup deadline; PUT is never resumed.
        objects.put(file, bytes)
        available(repository.complete(file, clock.instant()))
    }

    suspend fun get(userId: String, id: UUID): StoredAttachment =
        available(repository.get(userId, id) ?: throw AttachmentException("attachment_not_found"))

    suspend fun find(userId: String, sourceKey: String): StoredAttachment? =
        repository.find(userId, sourceKey)?.let(::available)

    suspend fun read(userId: String, id: UUID): ByteArray = transfers.withPermit {
        val file = get(userId, id)
        objects.read(file).also { available(file) }
    }

    suspend fun cleanup(): Int {
        var removed = 0
        // One row per claim, so each lease covers a single S3 deletion.
        repeat(ATTACHMENT_CLEANUP_BATCH_SIZE) {
            val file = repository.claim(clock.instant()) ?: return removed
            try {
                objects.delete(file)
                repository.remove(file)
                removed++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Never log exception messages, response bodies or signed transport details.
                val s3 = error as? S3Exception
                logger.warn("Attachment cleanup failed for file {}: exceptionClass={}, statusCode={}, s3ErrorCode={}",
                    file.id, error.javaClass.name, s3?.statusCode(), s3?.awsErrorDetails()?.errorCode())
            }
        }
        return removed
    }

    private fun available(file: StoredAttachment): StoredAttachment {
        if (file.state == "deleting" || file.expiresAt?.isAfter(clock.instant()) == false) {
            throw AttachmentException("attachment_expired")
        }
        if (file.state != "ready") throw AttachmentException("attachment_not_ready")
        return file
    }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(AttachmentStorage::class.java)
    }
}
