package ru.souz.ui.main.usecases

import ru.souz.ui.main.ChatAttachedFile

interface PathPicker {
    suspend fun pickFiles(allowMultiple: Boolean = true): Result<List<String>>
    suspend fun pickDirectory(): Result<String?>
}

object NoopPathPicker : PathPicker {
    override suspend fun pickFiles(allowMultiple: Boolean): Result<List<String>> =
        Result.success(emptyList())

    override suspend fun pickDirectory(): Result<String?> =
        Result.success(null)
}

interface DroppedFilePathExtractor {
    fun extractDroppedFilePaths(payload: Any): List<String>
}

object NoopDroppedFilePathExtractor : DroppedFilePathExtractor {
    override fun extractDroppedFilePaths(payload: Any): List<String> = emptyList()
}

interface AttachmentMetadataProvider {
    fun attachmentForPath(path: String): ChatAttachedFile?
}
