package ru.souz.llms

enum class FilePresentation { document, image, voice, audio }

/** Provider-neutral metadata shared by messages, tool results and hosts. */
data class FileContent(
    /** Immutable host-owned identity, never a URL, provider upload ID or filesystem path. */
    val fileId: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val presentation: FilePresentation = FilePresentation.document,
)

/** Per-message limits shared by agent selection and host commit validation. */
const val MAX_MESSAGE_FILES: Int = 10
const val MAX_MESSAGE_FILE_BYTES: Long = 100_000_000L
