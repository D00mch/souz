package ru.souz.llms

/** Durable host-owned identity. Never a provider upload ID, URL, or filesystem path. */
data class FileReference(val fileId: String)

enum class FilePresentation { document, image, voice, audio }

/** Provider-neutral metadata shared by messages, tool results and hosts. */
data class FileContent(
    val file: FileReference,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val presentation: FilePresentation = FilePresentation.document,
)
