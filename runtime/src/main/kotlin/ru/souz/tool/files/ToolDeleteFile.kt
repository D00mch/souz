package ru.souz.tool.files

import org.slf4j.LoggerFactory
import ru.souz.llms.ToolInvocationMeta
import ru.souz.tool.*

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

    override suspend fun suspendInvoke(input: Input, meta: ToolInvocationMeta): String {
        val fixedPath = filesToolUtil.applyDefaultEnvs(input.path, meta)
        val result = permissionBroker?.requestPermission(
            "Delete file or folder",
            linkedMapOf("path" to fixedPath)
        )
        if (result is ToolPermissionResult.No) return result.msg
        return invoke(input, meta)
    }

    override fun invoke(input: Input, meta: ToolInvocationMeta): String {
        val path = filesToolUtil.resolvePath(input.path, meta)
        if (!path.exists) {
            throw BadInputException("Invalid path: ${input.path}")
        }
        filesToolUtil.moveToTrash(path, l, meta)
        return "Path moved to Trash from ${input.path}"
    }
}
