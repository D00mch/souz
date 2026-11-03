package ru.souz.backend.attachments

import java.time.Duration
import java.time.Instant
import java.util.UUID
import ru.souz.backend.crypto.sha256Hex

internal val ATTACHMENT_RETENTION: Duration = Duration.ofDays(30)
internal val ATTACHMENT_PENDING_LIFETIME: Duration = Duration.ofMinutes(10)
internal const val ATTACHMENT_CLEANUP_CONSECUTIVE_FAILURE_LIMIT = 3
// Outlasts one claimed deletion: the 120-second S3 call plus a pooled metadata write.
internal val ATTACHMENT_CLEANUP_LEASE: Duration = Duration.ofMinutes(5)

data class StoredAttachment(
    val id: UUID,
    val userId: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    val state: String,
    val createdAt: Instant,
    val storedAt: Instant?,
    val expiresAt: Instant?,
    val cleanupDueAt: Instant,
    val objectKey: String,
)

internal fun attachmentObjectKey(userId: String, id: UUID): String = "files/${userId.sha256Hex()}/$id"

/** A raw key inside the configured bucket, never a URL or filesystem path. */
internal fun validateAttachmentObjectKey(key: String) {
    if (key.isBlank() || key.toByteArray(Charsets.UTF_8).size > 1024 || key.any { it.isISOControl() }) {
        throw AttachmentException("invalid_attachment_object_key")
    }
}

class AttachmentException(val code: String) : IllegalStateException(code)
