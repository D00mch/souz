package ru.gigadesk.tool.files

import org.slf4j.LoggerFactory
import ru.gigadesk.tool.BadInputException
import ru.gigadesk.tool.FewShotExample
import ru.gigadesk.tool.InputParamDescription
import ru.gigadesk.tool.ReturnParameters
import ru.gigadesk.tool.ReturnProperty
import ru.gigadesk.tool.ToolSetup
import ru.gigadesk.db.ConfigStore
import ru.gigadesk.db.SettingsProvider
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

class ToolMoveFile(private val filesToolUtil: FilesToolUtil) : ToolSetup<ToolMoveFile.Input> {
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

    override fun invoke(input: Input): String {
        val sourceFile = File(filesToolUtil.applyDefaultEnvs(input.sourcePath))
        val destinationFile = File(filesToolUtil.applyDefaultEnvs(input.destinationPath))
        filesToolUtil.requirePathIsSave(sourceFile)
        filesToolUtil.requirePathIsSave(destinationFile)
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
        try {
            Files.move(sourcePath, destinationPath, StandardCopyOption.ATOMIC_MOVE)
        } catch (exception: AtomicMoveNotSupportedException) {
            l.warn("Failed to make an atomic move", exception)
            Files.move(sourcePath, destinationPath)
        }
        return "File moved to ${input.destinationPath}"
    }
}

fun main() {
    val filesToolUtil = FilesToolUtil(SettingsProvider(ConfigStore))
    val result = ToolMoveFile(filesToolUtil).invoke(
        ToolMoveFile.Input(
            "~/Downloads/tmp.txt",
            destinationPath = "~/Downloads/articles/tmp.txt",
        )
    )
    println(result)
}
