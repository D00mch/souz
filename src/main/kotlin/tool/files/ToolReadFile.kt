package com.dumch.tool.files

import com.dumch.tool.BadInputException
import com.dumch.tool.InputParamDescription
import com.dumch.tool.ToolSetup
import java.io.File

object ToolReadFile : ToolSetup<ToolReadFile.Input> {
    override val name = "ReadFile"
    override val description = "Retrieve the contents of a specified file using a relative path. " +
            "Use this to read a file's contents. Avoid using it with directory paths"

    override fun invoke(input: Input): String {
        val path = input.path
        val file = File(path)
        if (!file.exists() || file.isDirectory) {
            throw BadInputException("Invalid file path: $path")
        }
        FilesToolUtil.requirePathIsSave(file)
        return file.readText()
    }

    data class Input(
        @InputParamDescription("A relative path pointing to a file in the project directory")
        val path: String
    )
}
