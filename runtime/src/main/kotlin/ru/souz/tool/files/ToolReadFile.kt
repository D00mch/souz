package ru.souz.tool.files

import ru.souz.llms.ToolInvocationMeta
import ru.souz.tool.*

class ToolReadFile(private val filesToolUtil: FilesToolUtil) : ToolSetup<ToolReadFile.Input> {
    data class Input(
        @InputParamDescription("A relative path pointing to a file in the project directory")
        val path: String
    )
    override val name = "ReadFile"
    override val description = "Retrieve the contents of a specified file using a relative path. " +
            "Use this to read a file's contents. Avoid using it with directory paths"
    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Прочти README",
            params = mapOf("path" to "README.md")
        )
    )
    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "File contents")
        )
    )

    override fun invoke(input: Input, meta: ToolInvocationMeta): String {
        val file = filesToolUtil.resolveSafeExistingFile(input.path, meta)
        val content = filesToolUtil.readUtf8TextFile(file, meta)
        if (content.length > 25000) {
            return "Error: content is too large, it will be difficult to correctly process so much data (limit 25000 chars)."
        }
        return content
    }

    override suspend fun suspendInvoke(input: Input, meta: ToolInvocationMeta): String = invoke(input, meta)
}
