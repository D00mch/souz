package ru.souz.backend.attachments

import java.time.Duration
import java.time.Instant
import java.util.UUID
import ru.souz.backend.crypto.sha256Hex

internal val ATTACHMENT_RETENTION = Duration.ofDays(30)
internal val ATTACHMENT_PENDING_LIFETIME = Duration.ofHours(24)

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
    val objectKey: String get() = "files/${userId.toByteArray(Charsets.UTF_8).sha256Hex()}/$id"
}

class AttachmentException(val code: String) : IllegalStateException(code)
