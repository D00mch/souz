package ru.souz.tool.files

import ru.souz.llms.ToolInvocationMeta
import ru.souz.tool.*
import java.io.File

class ToolNewFile(private val filesToolUtil: FilesToolUtil) : ToolSetup<ToolNewFile.Input> {
    data class Input(
        @InputParamDescription("The path where the file or folder will be created; add a trailing slash to create a folder")
        val path: String,
        @InputParamDescription("The content to be written to the new file")
        val text: String
    )
    override val name = "NewFile"
    override val description = "Creates a new TEXT file at the given path with the provided content." +
            " If the path ends with a slash, creates a folder instead. forbidden: .xlsx, .xls, .png, .jpg, .pdf."
    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Create notes.txt with greeting",
            params = mapOf("path" to "notes.txt", "text" to "Hello!\n")
        ),
        FewShotExample(
            request = "Create a folder named drafts",
            params = mapOf("path" to "drafts/", "text" to "")
        )
    )
    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Creation status")
        )
    )

    override fun invoke(input: Input, meta: ToolInvocationMeta): String {
        val isDirectoryRequest = input.path.endsWith("/") || input.path.endsWith(File.separator)
        val target = filesToolUtil.resolvePath(input.path)
        if (!filesToolUtil.isPathSafe(target)) {
            throw ForbiddenFolder(target.path)
        }
        if (target.exists) {
            val typeLabel = if (isDirectoryRequest) "Folder" else "File"
            throw BadInputException("$typeLabel already exists: ${input.path}. Use EditFile to modify existing files.")
        }
        if (isDirectoryRequest) {
            filesToolUtil.createDirectory(target)
            return "Folder created at ${input.path}"
        }
        filesToolUtil.writeUtf8TextFile(target, input.text)
        return "File created at ${input.path}"
    }
}
