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
import ru.souz.llms.runtime.ImageSource
import software.amazon.awssdk.services.s3.model.S3Exception

class AttachmentStorage(
    private val config: AttachmentStorageConfig,
    private val repository: PostgresAttachmentRepository,
    private val objects: S3AttachmentObjects,
    private val clock: Clock,
) {
    // Limits active transfers, not total heap: callers can retain returned arrays.
    private val transfers = Semaphore(config.maxConcurrentUploads)

    suspend fun save(userId: String, sourceKey: String, name: String, input: InputStream): StoredAttachment =
        save(userId, name, input) { sourceKey }

    /** Reads and hashes [input] once under the transfer limit; [sourceKey] receives the content SHA-256. */
    suspend fun save(userId: String, name: String, input: InputStream, sourceKey: (String) -> String): StoredAttachment = transfers.withPermit {
        val bytes = runInterruptible(Dispatchers.IO) { input.use { it.readAttachmentBytes(config.maxFileBytes) } }
        val sha256 = bytes.sha256Hex()
        val key = sourceKey(sha256)
        require(userId.isNotBlank() && key.isNotBlank() && key.length <= 512)
        val safeName = attachmentName(name)
        val file = repository.reserve(userId, key, safeName, attachmentMime(bytes, safeName),
            bytes.size.toLong(), sha256, clock.instant(), config)
        if (file.state == "ready") return@withPermit available(file)
        // Failed/canceled uploads stay pending until their cleanup deadline; PUT is never resumed.
        objects.put(file, bytes)
        available(repository.complete(file, clock.instant()))
    }

    /** Reserve durable byte/count quota before decoding or downloading a client tool image. */
    internal suspend fun saveClientImage(userId: String, sourceKey: String, limit: Int, declaredMime: String?,
        load: suspend (Int) -> ByteArray): StoredAttachment = transfers.withPermit {
        require(userId.isNotBlank() && sourceKey.isNotBlank() && sourceKey.length <= 512 && limit > 0)
        val bound = minOf(limit, config.maxFileBytes)
        val reserved = repository.reserveClientImage(userId, sourceKey, bound, clock.instant(), config)
        if (reserved.state == "ready") return@withPermit available(reserved)
        // Failure or cancellation leaves the reservation for the normal pending cleanup, including
        // cancellation just after its transaction commits. No untracked S3 object can be created.
        val bytes = load(bound)
        require(bytes.size <= bound)
        val source = ImageSource.read(bytes)
        require(declaredMime == null || declaredMime == source.mimeType)
        val file = repository.prepareClientImage(reserved, "tool-image.${source.mimeType.substringAfter('/')}",
            source.mimeType, bytes.size, bytes.sha256Hex(), clock.instant())
        objects.put(file, bytes)
        available(repository.complete(file, clock.instant()))
    }

    suspend fun get(userId: String, id: UUID): StoredAttachment =
        available(repository.get(userId, id) ?: throw AttachmentException("attachment_not_found"))

    internal suspend fun getAll(userId: String, ids: Set<UUID>): Map<UUID, StoredAttachment> {
        if (ids.isEmpty()) return emptyMap()
        val files = repository.getAll(userId, ids)
        if (files.size != ids.size) throw AttachmentException("attachment_not_found")
        return files.mapValues { available(it.value) }
    }

    suspend fun importUpload(userId: String, objectKey: String, name: String): StoredAttachment = transfers.withPermit {
        validateAttachmentObjectKey(objectKey)
        val safeName = attachmentName(name)
        repository.getByObjectKey(userId, objectKey)?.let { existing ->
            available(existing)
            if (existing.name != safeName) throw AttachmentException("attachment_idempotency_conflict")
            return@withPermit existing
        }
        val bytes = try {
            objects.readUpload(objectKey, config.maxFileBytes)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (known: AttachmentException) {
            throw known
        } catch (error: S3Exception) {
            throw AttachmentException(if (error.statusCode() == 404) "attachment_not_found" else "attachment_operation_failed")
        } catch (_: Exception) {
            throw AttachmentException("attachment_operation_failed")
        }
        val now = clock.instant()
        available(repository.registerUpload(StoredAttachment(
            UUID.randomUUID(), userId, safeName, attachmentMime(bytes, safeName), bytes.size.toLong(), bytes.sha256Hex(),
            "ready", now, now, now.plus(ATTACHMENT_RETENTION), now.plus(ATTACHMENT_RETENTION), objectKey,
        ), config))
    }

    suspend fun find(userId: String, sourceKey: String): StoredAttachment? =
        repository.find(userId, sourceKey)?.let(::available)

    suspend fun read(userId: String, id: UUID): ByteArray = load(userId, id).second

    /** Returns metadata and bytes from one row lookup, checking availability again after the transfer. */
    suspend fun load(userId: String, id: UUID): Pair<StoredAttachment, ByteArray> = transfers.withPermit {
        val file = get(userId, id)
        file to readAvailable(file)
    }

    /** Read a file from an owner-scoped lookup already performed by this operation. */
    internal suspend fun read(file: StoredAttachment): ByteArray = transfers.withPermit { readAvailable(available(file)) }

    private suspend fun readAvailable(file: StoredAttachment): ByteArray = objects.read(file).also { available(file) }

    suspend fun cleanup(): Int {
        var removed = 0
        var failures = 0
        // One row per claim, so each lease covers a single S3 deletion. Consecutive failures end the pass early,
        // so an unavailable S3 costs a few attempts per worker delay instead of one per due row.
        while (failures < ATTACHMENT_CLEANUP_CONSECUTIVE_FAILURE_LIMIT) {
            val file = repository.claim(clock.instant()) ?: break
            try {
                objects.delete(file)
                repository.remove(file)
                removed++
                failures = 0
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failures++
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
