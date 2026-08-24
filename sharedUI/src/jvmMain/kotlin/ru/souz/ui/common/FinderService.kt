package ru.souz.ui.common

import souz.sharedui.generated.resources.Res
import souz.sharedui.generated.resources.*
import org.jetbrains.compose.resources.getString
import java.awt.Desktop
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File
import java.net.URI
import javax.swing.JFileChooser

object FinderService {
    private val pathMetadataProvider = FileSystemPathMetadataProvider()

    fun normalizePath(rawPath: String): String? = pathMetadataProvider.normalizePath(rawPath)

    fun displayName(rawPath: String): String = pathMetadataProvider.displayName(rawPath)

    fun isDirectory(rawPath: String): Boolean = pathMetadataProvider.isDirectory(rawPath)

    suspend fun openInFinder(rawPath: String): Result<Unit> = runCatching {
        val normalized = normalizePath(rawPath)
            ?: error(getString(Res.string.error_empty_path))

        val target = File(normalized)
        require(target.exists()) { getString(Res.string.error_path_not_found).format(normalized) }

        if (isMacOs()) {
            val command = if (target.isDirectory) {
                listOf("open", target.absolutePath)
            } else {
                listOf("open", "-R", target.absolutePath)
            }
            ProcessBuilder(command).start()
            return@runCatching
        }

        require(Desktop.isDesktopSupported()) { getString(Res.string.error_desktop_not_supported) }
        val desktop = Desktop.getDesktop()
        require(desktop.isSupported(Desktop.Action.OPEN)) { getString(Res.string.error_opening_paths_not_supported) }

        val openTarget = if (target.isDirectory) target else target.parentFile ?: target
        desktop.open(openTarget)
    }

    suspend fun chooseFilesFromFinder(allowMultiple: Boolean = true): Result<List<String>> = runCatching {
        val title = getString(Res.string.title_select_files)
        val chooser = JFileChooser().apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.FILES_ONLY
            isMultiSelectionEnabled = allowMultiple
            isAcceptAllFileFilterUsed = true
        }

        val result = chooser.showOpenDialog(null)
        if (result != JFileChooser.APPROVE_OPTION) return@runCatching emptyList()

        val selectedFiles = if (allowMultiple) {
            chooser.selectedFiles.toList()
        } else {
            listOfNotNull(chooser.selectedFile)
        }

        selectedFiles
            .mapNotNull { file ->
                val rawPath = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
                normalizePath(rawPath)
            }
    }

    fun extractDroppedFilePaths(transferable: Transferable): List<String> {
        val fileListPaths = runCatching {
            @Suppress("UNCHECKED_CAST")
            (transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<File>)
                .orEmpty()
                .mapNotNull { file ->
                    val rawPath = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
                    normalizePath(rawPath)
                }
        }.getOrDefault(emptyList())
        if (fileListPaths.isNotEmpty()) return fileListPaths

        val uriListPaths = runCatching {
            val data = transferable.getTransferData(DataFlavor.stringFlavor) as? String ?: return@runCatching emptyList()
            data
                .lineSequence()
                .map(String::trim)
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val uri = runCatching { URI(line) }.getOrNull() ?: return@mapNotNull null
                    if (!uri.scheme.equals("file", ignoreCase = true)) return@mapNotNull null
                    val path = runCatching { File(uri).canonicalPath }.getOrElse { File(uri).absolutePath }
                    normalizePath(path)
                }
                .toList()
        }.getOrDefault(emptyList())
        if (uriListPaths.isNotEmpty()) return uriListPaths

        return emptyList()
    }

    private fun isMacOs(): Boolean =
        System.getProperty("os.name")
            ?.contains("mac", ignoreCase = true)
            ?: false
}
