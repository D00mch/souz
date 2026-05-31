package ru.souz.ui.main.usecases

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.souz.ui.main.ChatAttachedFile
import java.util.Locale

class ChatAttachmentsUseCase(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val pathPicker: PathPicker = NoopPathPicker,
    private val droppedFilePathExtractor: DroppedFilePathExtractor = NoopDroppedFilePathExtractor,
    private val attachmentMetadataProvider: AttachmentMetadataProvider = FileSystemAttachmentMetadataProvider(),
) {
    suspend fun pickFilesFromFinder(): Result<List<String>> = pathPicker.pickFiles()

    fun extractDroppedFilePathsNow(payload: Any): List<String> =
        droppedFilePathExtractor.extractDroppedFilePaths(payload).takeLast(MAX_ATTACHMENTS)

    suspend fun addFiles(
        existing: List<ChatAttachedFile>,
        rawPaths: List<String>,
    ): List<ChatAttachedFile> = withContext(ioDispatcher) {
        if (rawPaths.isEmpty()) return@withContext existing

        val existingByPath = LinkedHashMap<String, ChatAttachedFile>(existing.size + rawPaths.size)
        existing.forEach { file ->
            existingByPath[pathKey(file.path)] = file
        }

        rawPaths.forEach { rawPath ->
            val attachment = attachmentMetadataProvider.attachmentForPath(rawPath) ?: return@forEach
            val key = pathKey(attachment.path)
            existingByPath.remove(key)
            existingByPath[key] = attachment
        }

        existingByPath.values.toList().takeLast(MAX_ATTACHMENTS)
    }

    suspend fun buildAttachmentsFromPaths(paths: List<String>): List<ChatAttachedFile> =
        addFiles(existing = emptyList(), rawPaths = paths)

    fun removeFile(
        existing: List<ChatAttachedFile>,
        rawPath: String,
    ): List<ChatAttachedFile> {
        val normalized = attachmentMetadataProvider.attachmentForPath(rawPath)?.path ?: rawPath
        return existing.filterNot { it.path.equals(normalized, ignoreCase = true) }
    }

    fun buildChatMessageWithAttachedPaths(
        input: String,
        attachedFiles: List<ChatAttachedFile>,
    ): String {
        val text = input.trim()
        val limitedAttachments = attachedFiles.takeLast(MAX_ATTACHMENTS)
        if (limitedAttachments.isEmpty()) return text

        val pathsBlock = limitedAttachments.joinToString(separator = "\n") { it.path }
        return when {
            text.isBlank() -> pathsBlock
            else -> "$text\n\n$pathsBlock"
        }
    }

    private fun pathKey(path: String): String = path.lowercase(Locale.ROOT)

    private companion object {
        const val MAX_ATTACHMENTS = 50
    }
}
