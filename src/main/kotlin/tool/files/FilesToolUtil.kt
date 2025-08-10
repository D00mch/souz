package com.dumch.tool.files

import com.dumch.tool.BadInputException
import java.io.File

object FilesToolUtil {
    private val projectRoot = File(".").canonicalFile

    fun isPathSafe(file: File): Boolean {
        val canonicalPath = file.canonicalFile
        return canonicalPath.startsWith(projectRoot)
    }

    @Throws(BadInputException::class)
    fun requirePathIsSave(file: File) {
        if (!isPathSafe(file)) {
            throw BadInputException("Access denied: File path must be within project directory")
        }
    }
}