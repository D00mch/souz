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
        val source = filesToolUtil.resolveSafeExistingFile(input.sourcePath)
        val destination = filesToolUtil.resolvePath(input.destinationPath)
        if (source.path == destination.path) {
            throw BadInputException("Source and destination paths must be different.")
        }
        if (destination.exists) {
            throw BadInputException("Destination file already exists: ${input.destinationPath}")
        }
        val destinationParentPath = destination.parentPath
            ?: throw BadInputException("Destination path must include a parent directory.")
        val destinationParent = filesToolUtil.resolvePath(destinationParentPath)
        if (destinationParent.exists && !destinationParent.isDirectory) {
            throw BadInputException("Destination parent is not a directory: ${destinationParent.path}")
        }
        filesToolUtil.movePath(source, destination, replaceExisting = false, createParents = true, logger = l)
        return "File moved to ${input.destinationPath}"
    }

}
