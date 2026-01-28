package ru.gigadesk.tool.files

import ru.gigadesk.tool.*
import java.io.File

class ToolModifyFile(private val filesToolUtil: FilesToolUtil) : ToolSetup<ToolModifyFile.Input> {
    data class Input(
        @InputParamDescription("The path to the file, including file name")
        val path: String,
        @InputParamDescription("Exact text to find in the file - must occur exactly once")
        val oldText: String,
        @InputParamDescription("Replacement text for the specified old_text")
        val newText: String,
    )
    override val name = "EditFile"
    override val description = "Replace text in a file. Replaces 'old_text' with 'new_text' in the specified file. "
    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Replace foo with bar in notes.txt",
            params = mapOf("path" to "notes.txt", "oldText" to "foo", "newText" to "bar")
        )
    )
    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Operation status")
        )
    )

    override fun invoke(input: Input): String {
        val file = File(filesToolUtil.applyDefaultEnvs(input.path))
        if (input.oldText == input.newText || input.path.isBlank() || input.oldText.isEmpty()) {
            throw BadInputException("Invalid input parameters")
        } else if (!file.exists()) {
            throw BadInputException("File does not exist")
        }
        filesToolUtil.requirePathIsSave(file)
        val content = file.readText()
        val newContent = content.replace(input.oldText, input.newText)
        file.writeText(newContent)
        return "OK"
    }
}
