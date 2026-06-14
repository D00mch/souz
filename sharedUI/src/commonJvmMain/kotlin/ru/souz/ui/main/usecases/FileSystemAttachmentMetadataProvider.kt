package ru.souz.ui.main.usecases

import ru.souz.ui.common.FileSystemPathMetadataProvider
import ru.souz.ui.common.PathMetadataProvider
import ru.souz.ui.main.ChatAttachedFile
import ru.souz.ui.main.ChatAttachmentType
import java.io.File
import java.util.Locale

open class FileSystemAttachmentMetadataProvider(
    private val pathMetadataProvider: PathMetadataProvider = FileSystemPathMetadataProvider(),
) : AttachmentMetadataProvider {
    override fun attachmentForPath(path: String): ChatAttachedFile? {
        val normalized = pathMetadataProvider.normalizePath(path) ?: return null
        val file = File(normalized)
        if (!file.exists() || !file.isFile) return null

        return ChatAttachedFile(
            path = normalized,
            displayName = pathMetadataProvider.displayName(normalized),
            sizeBytes = runCatching { file.length() }.getOrDefault(0L),
            type = detectType(file),
            thumbnailBytes = thumbnailBytes(file),
        )
    }

    protected open fun thumbnailBytes(file: File): ByteArray? = null

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

    protected fun isImage(file: File): Boolean =
        file.extension.lowercase(Locale.ROOT) in imageExtensions

    private companion object {
        val documentExtensions = setOf("doc", "docx", "txt", "rtf", "md")
        val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "tiff", "svg")
        val spreadsheetExtensions = setOf("xls", "xlsx", "csv")
        val videoExtensions = setOf("mp4", "avi", "mov", "mkv", "webm")
        val audioExtensions = setOf("mp3", "wav", "ogg", "m4a", "aac", "flac")
        val archiveExtensions = setOf("zip", "rar", "7z", "tar", "gz")
    }
}
