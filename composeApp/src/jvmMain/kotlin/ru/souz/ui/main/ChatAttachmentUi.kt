package ru.souz.ui.main

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Image as ImageIcon
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import org.jetbrains.skia.Image as SkiaImage

internal data class ChatAttachmentUiStyle(
    val icon: ImageVector,
    val background: Color,
    val border: Color,
    val iconTint: Color,
)

internal fun chatAttachmentUiStyle(type: ChatAttachmentType): ChatAttachmentUiStyle = when (type) {
    ChatAttachmentType.DOCUMENT -> ChatAttachmentUiStyle(
        icon = Icons.Rounded.Description,
        background = Color(0x263B82F6),
        border = Color(0x403B82F6),
        iconTint = Color(0xFF3B82F6)
    )
    ChatAttachmentType.IMAGE -> ChatAttachmentUiStyle(
        icon = Icons.Rounded.ImageIcon,
        background = Color(0x268B5CF6),
        border = Color(0x408B5CF6),
        iconTint = Color(0xFF8B5CF6)
    )
    ChatAttachmentType.PDF -> ChatAttachmentUiStyle(
        icon = Icons.Rounded.PictureAsPdf,
        background = Color(0x26EF4444),
        border = Color(0x40EF4444),
        iconTint = Color(0xFFEF4444)
    )
    ChatAttachmentType.SPREADSHEET -> ChatAttachmentUiStyle(
        icon = Icons.Rounded.TableChart,
        background = Color(0x2622C55E),
        border = Color(0x4022C55E),
        iconTint = Color(0xFF22C55E)
    )
    ChatAttachmentType.VIDEO -> ChatAttachmentUiStyle(
        icon = Icons.Rounded.Movie,
        background = Color(0x26F59E0B),
        border = Color(0x40F59E0B),
        iconTint = Color(0xFFF59E0B)
    )
    ChatAttachmentType.AUDIO -> ChatAttachmentUiStyle(
        icon = Icons.Rounded.Audiotrack,
        background = Color(0x26EC4899),
        border = Color(0x40EC4899),
        iconTint = Color(0xFFEC4899)
    )
    ChatAttachmentType.ARCHIVE -> ChatAttachmentUiStyle(
        icon = Icons.Rounded.Archive,
        background = Color(0x26F59E0B),
        border = Color(0x40F59E0B),
        iconTint = Color(0xFFF59E0B)
    )
    ChatAttachmentType.OTHER -> ChatAttachmentUiStyle(
        icon = Icons.AutoMirrored.Rounded.InsertDriveFile,
        background = Color(0x14FFFFFF),
        border = Color(0x26FFFFFF),
        iconTint = Color(0xB3FFFFFF)
    )
}

internal fun decodeAttachmentThumbnail(bytes: ByteArray?): ImageBitmap? {
    if (bytes == null || bytes.isEmpty()) return null
    return runCatching {
        SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
    }.getOrNull()
}

internal fun formatAttachmentFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    return when {
        bytes >= gb -> String.format("%.1f GB", bytes / gb)
        bytes >= mb -> String.format("%.1f MB", bytes / mb)
        bytes >= kb -> String.format("%.1f KB", bytes / kb)
        else -> "$bytes B"
    }
}
