package ru.souz.ui.main.usecases

import org.jetbrains.compose.resources.getString
import ru.souz.ui.common.FinderService
import souz.sharedui.generated.resources.Res
import souz.sharedui.generated.resources.title_select_folder
import java.awt.RenderingHints
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JFileChooser

class DesktopPathPicker : PathPicker {
    override suspend fun pickFiles(allowMultiple: Boolean): Result<List<String>> =
        FinderService.chooseFilesFromFinder(allowMultiple)

    override suspend fun pickDirectory(): Result<String?> = runCatching {
        val chooser = JFileChooser().apply {
            dialogTitle = getString(Res.string.title_select_folder)
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isMultiSelectionEnabled = false
            isAcceptAllFileFilterUsed = false
        }
        val result = chooser.showOpenDialog(null)
        if (result != JFileChooser.APPROVE_OPTION) return@runCatching null

        val selected = chooser.selectedFile ?: return@runCatching null
        runCatching { selected.canonicalPath }.getOrElse { selected.absolutePath }
    }
}

class DesktopDroppedFilePathExtractor : DroppedFilePathExtractor {
    override fun extractDroppedFilePaths(payload: Any): List<String> {
        val transferable = payload as? Transferable ?: return emptyList()
        return FinderService.extractDroppedFilePaths(transferable)
    }
}

class DesktopAttachmentMetadataProvider : FileSystemAttachmentMetadataProvider() {
    override fun thumbnailBytes(file: File): ByteArray? {
        if (!isImage(file)) return null

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
}
