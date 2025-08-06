package com.dumch.tool.files

import com.dumch.tool.BadInputException
import com.dumch.tool.InputParamDescription
import com.dumch.tool.ToolSetup
import java.io.File

object ToolModifyFile : ToolSetup<ToolModifyFile.Input> {
    override val name = "EditFile"
    override val description = "Replace text in a file. Replaces 'old_text' with 'new_text' in the specified file. "

    override fun invoke(input: Input): String {
        val file = File(input.path)
        if (input.oldText == input.newText || input.path.isBlank() || input.oldText.isEmpty()) {
            throw BadInputException("Invalid input parameters")
        } else if (!file.exists()) {
            throw BadInputException("File does not exist")
        }
        FilesToolUtil.requirePathIsSave(file)
        val content = file.readText()
        val newContent = content.replace(input.oldText, input.newText)
        file.writeText(newContent)
        return "OK"
    }

    data class Input(
        @InputParamDescription("The path to the file, including file name")
        val path: String,
        @InputParamDescription("Exact text to find in the file - must occur exactly once")
        val oldText: String,
        @InputParamDescription("Replacement text for the specified old_text")
        val newText: String,
    )
}