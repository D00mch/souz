package ru.souz.backend.attachments

import java.io.InputStream
import org.apache.tika.Tika

private val tika = Tika()

internal fun InputStream.readAttachmentBytes(limit: Int): ByteArray {
    val bytes = readNBytes(limit + 1)
    if (bytes.size > limit) throw AttachmentException("attachment_too_large")
    return bytes
}

internal fun attachmentName(raw: String): String = raw.substringAfterLast('/').substringAfterLast('\\')
    .filterNot { it.isISOControl() }.trim().take(180).takeUnless { it.isBlank() || it in setOf(".", "..") }
    ?: "attachment"

internal fun attachmentMime(bytes: ByteArray, name: String): String {
    val detected = tika.detect(bytes)
    // Filename only refines already-detected text; it cannot turn executable bytes into a document.
    return when {
        detected == "text/plain" && name.endsWith(".md", ignoreCase = true) -> "text/markdown"
        detected == "application/ogg" && bytes.take(256).toByteArray().toString(Charsets.ISO_8859_1).contains("OpusHead") -> "audio/ogg"
        else -> detected
    }
}
