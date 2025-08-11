package com.dumch.tool.files

import com.dumch.tool.*
import java.io.File

object ToolNewFile : ToolSetup<ToolNewFile.Input> {
    override val name = "NewFile"
    override val description = "Creates a new file at the given path with the provided content."
    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Create notes.txt with greeting",
            params = mapOf("path" to "notes.txt", "text" to "Hello")
        )
    )
    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Creation status")
        )
    )

    override fun invoke(input: Input): String {
        val file = File(input.path)
        if (file.exists()) {
            throw BadInputException("File already exists: ${input.path}")
        }
        file.parentFile?.mkdirs()
        file.writeText(input.text)
        return "File created at ${input.path}"
    }

    data class Input(
        @InputParamDescription("The path where the file will be created, including filename")
        val path: String,
        @InputParamDescription("The content to be written to the new file")
        val text: String
    )
}
