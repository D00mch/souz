package ru.souz.tool.files

import org.slf4j.LoggerFactory
import ru.souz.tool.BadInputException
import ru.souz.tool.FewShotExample
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolPermissionBroker
import ru.souz.tool.ToolPermissionResult
import ru.souz.tool.ToolSetup
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

class ToolMoveFile(
    private val filesToolUtil: FilesToolUtil,
    private val permissionBroker: ToolPermissionBroker? = null,
) : ToolSetup<ToolMoveFile.Input> {
    private val l = LoggerFactory.getLogger(ToolMoveFile::class.java)

    data class Input(
        @InputParamDescription("The full path to the file (name included) to move")
        val sourcePath: String,
        @InputParamDescription("The full destination path (including filename) where the file will be moved.")
        val destinationPath: String,
    )

    override val name = "MoveFile"
    override val description = "Moves a file from the source path to the destination path. Use ~ as the Home dir"
    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Перемести report.txt в archive/report.txt",
            params = mapOf("sourcePath" to "~/Desktop/Скрины/report.txt", "destinationPath" to "~/Desktop/report.txt")
        )
    )
    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Move status")
        )
    )

    override suspend fun suspendInvoke(input: Input): String {
        val fixedSourcePath = filesToolUtil.applyDefaultEnvs(input.sourcePath)
        val fixedDestinationPath = filesToolUtil.applyDefaultEnvs(input.destinationPath)
        val result = permissionBroker?.requestPermission(
            "Move file",
            linkedMapOf(
                "sourcePath" to fixedSourcePath,
                "destinationPath" to fixedDestinationPath,
            )
        )
        if (result is ToolPermissionResult.No) return result.msg
        return invoke(input)
    }

    override fun invoke(input: Input): String {
        val fixedSourcePath = filesToolUtil.applyDefaultEnvs(input.sourcePath)
        val fixedDestinationPath = filesToolUtil.applyDefaultEnvs(input.destinationPath)
        val sourceFile = File(fixedSourcePath)
        val destinationFile = File(fixedDestinationPath)
        if (!filesToolUtil.isPathSafe(sourceFile)) {
            throw BadInputException("Forbidden directory: $fixedSourcePath. User explicitly restricted this path. Inform him")
        }
        if (!filesToolUtil.isPathSafe(destinationFile)) {
            throw BadInputException("Forbidden directory: $fixedDestinationPath. User explicitly restricted this path. Inform him")
        }
        val sourcePath = sourceFile.toPath().toAbsolutePath().normalize()
        val destinationPath = destinationFile.toPath().toAbsolutePath().normalize()
        if (sourcePath == destinationPath) {
            throw BadInputException("Source and destination paths must be different.")
        }
        if (!Files.exists(sourcePath, LinkOption.NOFOLLOW_LINKS) ||
            !Files.isRegularFile(sourcePath, LinkOption.NOFOLLOW_LINKS)
        ) {
            throw BadInputException("Invalid source file path: ${input.sourcePath}")
        }
        if (destinationFile.exists()) {
            throw BadInputException("Destination file already exists: ${input.destinationPath}")
        }
        val destinationParent = destinationFile.parentFile
            ?: throw BadInputException("Destination path must include a parent directory.")
        if (destinationParent.exists() && !destinationParent.isDirectory) {
            throw BadInputException("Destination parent is not a directory: ${destinationParent.path}")
        }
        if (!destinationParent.exists() && !destinationParent.mkdirs()) {
            throw BadInputException("Failed to create destination directory: ${destinationParent.path}")
        }
        if (!Files.isWritable(destinationParent.toPath())) {
            throw BadInputException("Destination directory is not writable: ${destinationParent.path}")
        }
        filesToolUtil.moveWithAtomicFallback(sourcePath, destinationPath, l)
        return "File moved to ${input.destinationPath}"
    }

}
