package ru.souz.backend.storage.postgres

import java.sql.Connection
import java.util.UUID
import ru.souz.backend.attachments.AttachmentException
import ru.souz.backend.attachments.attachmentId
import ru.souz.backend.attachments.requireMatchingPresentation
import ru.souz.llms.FileContent
import ru.souz.llms.MAX_MESSAGE_FILES
import ru.souz.llms.MAX_MESSAGE_FILE_BYTES

/** Validate trusted metadata again inside the message commit transaction. */
internal fun Connection.validateAttachments(userId: String, files: List<FileContent>) {
    if (availableAttachments(userId, files).size != files.size) throw AttachmentException("attachment_unavailable")
}

/**
 * Returns the [files] that are still owned, ready, unexpired and unchanged, locking their rows for the commit.
 * Limits, uniqueness and presentation are structural and fail the whole list.
 */
internal fun Connection.availableAttachments(userId: String, files: List<FileContent>): List<FileContent> {
    require(files.size <= MAX_MESSAGE_FILES && files.sumOf { it.sizeBytes } <= MAX_MESSAGE_FILE_BYTES) { "attachment_message_too_large" }
    require(files.distinctBy { it.file }.size == files.size) { "attachment_duplicate" }
    files.forEach { it.requireMatchingPresentation() }
    if (files.isEmpty()) return files
    val ids = files.map { attachmentId(it.file.fileId) }
    val stored = prepareStatement("""
        select id, name, mime_type, size_bytes from stored_attachments
        where user_id = ? and id = any(?) and state = 'ready' and expires_at > now()
        for share
    """.trimIndent()).use { statement ->
        statement.setString(1, userId)
        statement.setArray(2, createArrayOf("uuid", ids.toTypedArray()))
        statement.executeQuery().use { rows ->
            buildMap {
                while (rows.next()) {
                    put(rows.getObject(1, UUID::class.java), Triple(rows.getString(2), rows.getString(3), rows.getLong(4)))
                }
            }
        }
    }
    return files.filterIndexed { index, file -> stored[ids[index]] == Triple(file.name, file.mimeType, file.sizeBytes) }
}
