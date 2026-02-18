package ru.souz.ui.main.usecases

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.souz.ui.common.FinderService
import ru.souz.ui.main.ChatAttachedFile
import ru.souz.ui.main.ChatAttachmentType
import java.awt.RenderingHints
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO

class ChatAttachmentsUseCase(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun pickFilesFromFinder(): Result<List<String>> = FinderService.chooseFilesFromFinder()

    fun extractDroppedFilePathsNow(transferable: Transferable): List<String> =
        FinderService.extractDroppedFilePaths(transferable).takeLast(MAX_ATTACHMENTS)

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
            val normalized = FinderService.normalizePath(rawPath) ?: return@forEach
            val file = File(normalized)
            if (!file.exists() || !file.isFile) return@forEach

            val attachment = ChatAttachedFile(
                path = normalized,
                displayName = FinderService.displayName(normalized),
                sizeBytes = runCatching { file.length() }.getOrDefault(0L),
                type = detectType(file),
                thumbnailBytes = if (isImage(file)) createImageThumbnail(file) else null,
            )
            val key = pathKey(normalized)
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
        val normalized = FinderService.normalizePath(rawPath) ?: return existing
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

    private fun createImageThumbnail(file: File): ByteArray? {
        val source = runCatching { ImageIO.read(file) }.getOrNull() ?: return null
        val width = source.width
        val height = source.height
        if (width <= 0 || height <= 0) return null

        val maxSide = 128.0
        val scale = minOf(1.0, maxSide / maxOf(width, height))
        val targetWidth = maxOf(1, (width * scale).toInt())
        val targetHeight = maxOf(1, (height * scale).toInt())

        val resized = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB)
        val graphics = resized.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null)
        } finally {
            graphics.dispose()
        }

        return runCatching {
            ByteArrayOutputStream().use { out ->
                ImageIO.write(resized, "png", out)
                out.toByteArray()
            }
        }.getOrNull()
    }

    private fun detectType(file: File): ChatAttachmentType {
        val ext = file.extension.lowercase(Locale.ROOT)
        return when {
            ext in documentExtensions -> ChatAttachmentType.DOCUMENT
            ext in imageExtensions -> ChatAttachmentType.IMAGE
            ext == "pdf" -> ChatAttachmentType.PDF
            ext in spreadsheetExtensions -> ChatAttachmentType.SPREADSHEET
            ext in videoExtensions -> ChatAttachmentType.VIDEO
            ext in audioExtensions -> ChatAttachmentType.AUDIO
            ext in archiveExtensions -> ChatAttachmentType.ARCHIVE
            else -> ChatAttachmentType.OTHER
        }
    }

    private fun isImage(file: File): Boolean = file.extension.lowercase(Locale.ROOT) in imageExtensions

    private fun pathKey(path: String): String = path.lowercase(Locale.ROOT)

    private companion object {
        const val MAX_ATTACHMENTS = 50
        val documentExtensions = setOf("doc", "docx", "txt", "rtf", "md")
        val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "tiff", "svg")
        val spreadsheetExtensions = setOf("xls", "xlsx", "csv")
        val videoExtensions = setOf("mp4", "avi", "mov", "mkv", "webm")
        val audioExtensions = setOf("mp3", "wav", "ogg", "m4a", "aac", "flac")
        val archiveExtensions = setOf("zip", "rar", "7z", "tar", "gz")
    }
}
