package ru.souz.backend.attachments

import java.time.Instant
import java.util.UUID

data class StoredAttachment(
    val id: UUID,
    val userId: String,
    val bucket: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    val state: String,
    val createdAt: Instant,
    val storedAt: Instant?,
    val expiresAt: Instant?,
) {
    val objectKey: String get() = "files/$id"
}

class AttachmentException(val code: String) : IllegalStateException(code)
