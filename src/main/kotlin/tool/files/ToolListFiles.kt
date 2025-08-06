package com.dumch.tool.files

import com.dumch.tool.BadInputException
import com.dumch.tool.InputParamDescription
import com.dumch.tool.ToolSetup
import java.io.File

object ToolListFiles : ToolSetup<ToolListFiles.Input> {
    override val name = "ListFiles"
    override val description = "Runs bash ls command at a given path. Dot (.) means current directory"

    override fun invoke(input: Input): String {
        val dirPath = input.path
        val base = File(dirPath)
        if (!base.exists() || !base.isDirectory) {
            throw BadInputException("Invalid directory path: $dirPath")
        }
        FilesToolUtil.requirePathIsSave(base)
    val files = base.walkTopDown() // sequence
        .filter { it != base }
        .map { file ->
            val relPath = file.relativeTo(base).path
            if (file.isDirectory) "$relPath/" else relPath
        }

        return files.joinToString(",", prefix = "[", postfix = "]")
    }

    data class Input(
        @InputParamDescription("Relative path to list files from")
        val path: String = "."
    )
}
