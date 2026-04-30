package ru.souz.service.files

import java.io.File

interface FilesService {
    val homeStr: String
    val homeDirectory: File

    fun applyDefaultEnvs(path: String): String
    fun isPathSafe(file: File): Boolean
    fun requirePathIsSave(file: File)
}
