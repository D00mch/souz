package ru.souz.backend.attachments

import java.io.InputStream
import org.apache.tika.Tika

private val tika = Tika()

internal fun InputStream.readAttachmentBytes(limit: Int): ByteArray = readNBytes(limit + 1).also {
    if (it.size > limit) throw AttachmentException("attachment_too_large")
}

internal fun attachmentName(raw: String): String = raw.substringAfterLast('/').substringAfterLast('\\')
    .filterNot { it.isISOControl() }.trim().take(180).dropLastWhile { it.isHighSurrogate() }
    .takeUnless { it.isBlank() || it == "." || it == ".." } ?: "attachment"

internal fun attachmentMime(bytes: ByteArray, name: String): String {
    val detected = tika.detect(bytes)
    // A filename may refine text, but cannot disguise binary content as a document.
    return if (detected == "text/plain" && name.endsWith(".md", ignoreCase = true)) "text/markdown" else detected
}
