package ru.gigadesk.tool.files

import ru.gigadesk.tool.*
import java.io.File

class ToolDeleteFile(
    private val filesToolUtil: FilesToolUtil,
    private val permissionBroker: ToolPermissionBroker? = null,
) : ToolSetup<ToolDeleteFile.Input> {
    data class Input(
        @InputParamDescription("The path of the file to delete")
        val path: String
    )
    override val name = "DeleteFile"
    override val description = "Deletes a file at the given path. Use ~ as the Home dir"
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

    override suspend fun suspendInvoke(input: Input): String {
        val fixedPath = filesToolUtil.applyDefaultEnvs(input.path)
        val result = permissionBroker?.requestPermission(
            "Удаляем файл?",
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
        if (!file.exists() || file.isDirectory) {
            throw BadInputException("Invalid file path: ${input.path}")
        }
        file.delete()
        return "File deleted at ${input.path}"
    }
}
