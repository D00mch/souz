package ru.gigadesk.tool.files

import ru.gigadesk.tool.BadInputException
import ru.gigadesk.tool.FewShotExample
import ru.gigadesk.tool.InputParamDescription
import ru.gigadesk.tool.ReturnParameters
import ru.gigadesk.tool.ReturnProperty
import ru.gigadesk.tool.ToolSetup
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object ToolMoveFile : ToolSetup<ToolMoveFile.Input> {
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
            request = "Перемести Снимок экрана 2026-01-22 в 23.23.52.png в папку Скрины на рабочем столе",
            params = mapOf("sourcePath" to "~/Desktop/Снимок экрана 2026-01-22 в 23.23.52.png", "destinationPath" to "~/Desktop/Скрины/Снимок экрана 2026-01-22 в 23.23.52.png")
        )
    )
    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Move status")
        )
    )

    override fun invoke(input: Input): String {
        val sourceFile = File(FilesToolUtil.applyDefaultEnvs(input.sourcePath))
        val destinationFile = File(FilesToolUtil.applyDefaultEnvs(input.destinationPath))
        if (!sourceFile.exists() || sourceFile.isDirectory) {
            throw BadInputException("Invalid source file path: ${input.sourcePath}")
        }
        if (destinationFile.exists()) {
            throw BadInputException("Destination file already exists: ${input.destinationPath}")
        }
        FilesToolUtil.requirePathIsSave(sourceFile)
        FilesToolUtil.requirePathIsSave(destinationFile)
        destinationFile.parentFile?.mkdirs()
        Files.move(sourceFile.toPath(), destinationFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
        return "File moved to ${input.destinationPath}"
    }
}

fun main() {
    val result = ToolMoveFile.invoke(ToolMoveFile.Input("/Users/duxx/Desktop/Снимок экрана 2026-01-22 в 23.23.52.png",
        "/Users/duxx/Desktop/Скрины/Снимок экрана 2026-01-22 в 23.23.52.png"))
    println(result)
}