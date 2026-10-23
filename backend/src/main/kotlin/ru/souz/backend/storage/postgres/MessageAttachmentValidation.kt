package ru.souz.backend.storage.postgres

import java.sql.Connection
import ru.souz.backend.attachments.AttachmentException
import ru.souz.backend.attachments.attachmentId
import ru.souz.llms.FileContent

/** Validate trusted metadata again inside the message commit transaction. */
internal fun Connection.validateAttachments(userId: String, files: List<FileContent>) {
    require(files.size <= 10 && files.sumOf { it.sizeBytes } <= 100_000_000) { "attachment_message_too_large" }
    require(files.distinctBy { it.file }.size == files.size) { "attachment_duplicate" }
    for (file in files) {
        if ((file.presentation == ru.souz.llms.FilePresentation.image && !file.mimeType.startsWith("image/")) ||
            (file.presentation in setOf(ru.souz.llms.FilePresentation.voice, ru.souz.llms.FilePresentation.audio) && !file.mimeType.startsWith("audio/"))) {
            throw AttachmentException("attachment_presentation_mismatch")
        }
        prepareStatement("""
            select name, mime_type, size_bytes from stored_attachments
            where user_id = ? and id = ? and state = 'ready' and expires_at > now()
            for share
        """.trimIndent()).use {
            it.setString(1, userId)
            it.setObject(2, attachmentId(file.file.fileId))
            it.executeQuery().use { rows ->
                if (!rows.next() || rows.getString(1) != file.name || rows.getString(2) != file.mimeType ||
                    rows.getLong(3) != file.sizeBytes) throw AttachmentException("attachment_unavailable")
            }
        }
    }
}
