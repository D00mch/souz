package ru.souz.backend.attachments

import java.time.Duration
import java.time.Instant
import java.util.UUID
import ru.souz.backend.crypto.sha256Hex

internal val ATTACHMENT_RETENTION: Duration = Duration.ofDays(30)
internal val ATTACHMENT_PENDING_LIFETIME: Duration = Duration.ofMinutes(10)
internal const val ATTACHMENT_CLEANUP_BATCH_SIZE = 100

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
) {
    val objectKey: String get() = "files/${userId.sha256Hex()}/$id"
}

class AttachmentException(val code: String) : IllegalStateException(code)
