package ru.souz.tool.files

import org.slf4j.LoggerFactory
import ru.souz.tool.*
import souz.composeapp.generated.resources.Res
import souz.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import java.awt.Desktop
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

class ToolDeleteFile(
    private val filesToolUtil: FilesToolUtil,
    private val permissionBroker: ToolPermissionBroker? = null,
) : ToolSetup<ToolDeleteFile.Input> {
    private val l = LoggerFactory.getLogger(ToolDeleteFile::class.java)

    data class Input(
        @InputParamDescription("The path of the file or folder to delete")
        val path: String
    )
    override val name = "DeleteFile"
    override val description = "Moves a file or folder to Trash at the given path. Use ~ as the Home dir"
    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Удали temp.txt",
            params = mapOf("path" to "temp.txt")
        )
    )
    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Deletion status")
        )
    )

    override fun describeAction(input: Input): ToolActionDescriptor? {
        val fixedPath = filesToolUtil.applyDefaultEnvs(input.path)
        val file = File(fixedPath)
        val isDirectory = file.isDirectory || input.path.endsWith("/") || input.path.endsWith(File.separator)
        return ToolActionDescriptor(
            kind = if (isDirectory) ToolActionKind.DELETE_FOLDER else ToolActionKind.DELETE_FILE,
            primary = if (isDirectory) {
                ToolActionValueFormatter.folderName(input.path)
            } else {
                ToolActionValueFormatter.fileName(input.path)
            },
        )
    }

    override suspend fun suspendInvoke(input: Input): String {
        val fixedPath = filesToolUtil.applyDefaultEnvs(input.path)
        val result = permissionBroker?.requestPermission(
            getString(Res.string.permission_delete_file),
            linkedMapOf("path" to fixedPath)
        )
        if (result is ToolPermissionResult.No) return result.msg
        return invoke(input)
    }

    override fun invoke(input: Input): String {
        val fixedPath = filesToolUtil.applyDefaultEnvs(input.path)
        val file = File(fixedPath)
        if (!filesToolUtil.isPathSafe(file)) {
            throw ForbiddenFolder(fixedPath)
        }
        if (!file.exists()) {
            throw BadInputException("Invalid path: ${input.path}")
        }
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)) {
            val movedToTrash = Desktop.getDesktop().moveToTrash(file)
            if (movedToTrash) {
                return "Path moved to Trash from ${input.path}"
            }
            l.warn("Desktop trash operation reported failure for {}", fixedPath)
        }

        val trashTargetPath = resolveFallbackTrashTarget(file)
        filesToolUtil.moveWithAtomicFallback(file.toPath(), trashTargetPath, l)
        return "Path moved to Trash from ${input.path}"
    }

    private fun resolveFallbackTrashTarget(file: File): Path {
        val fileName = file.name
        val candidateDirectories = listOfNotNull(
            if (isWindows()) null else File(System.getProperty("user.home"), ".Trash"),
            if (isWindows()) null else File(System.getProperty("user.home"), ".local/share/Trash/files"),
            File(System.getProperty("java.io.tmpdir"), "souz-trash"),
        )
        val trashDirectory = candidateDirectories.firstOrNull { directory ->
            directory.exists() || directory.mkdirs()
        } ?: throw BadInputException("Unable to resolve Trash directory")

        var trashTarget = trashDirectory.toPath().resolve(fileName)
        if (trashTarget.exists()) {
            val withSuffix = "${file.nameWithoutExtension}-${System.currentTimeMillis()}"
            val extensionSuffix = if (file.extension.isBlank()) "" else ".${file.extension}"
            trashTarget = trashDirectory.toPath().resolve(withSuffix + extensionSuffix)
        }
        if (Files.exists(trashTarget)) {
            throw BadInputException("Unable to move file to Trash. Target exists: $trashTarget")
        }
        return trashTarget
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name")?.lowercase()?.contains("win") == true
}
