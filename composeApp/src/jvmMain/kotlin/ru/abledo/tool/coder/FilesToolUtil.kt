package ru.abledo.tool.files

import ru.abledo.tool.BadInputException
import java.io.File
import java.io.IOException
import java.io.InputStream

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

    fun resourceStream(path: String): InputStream =
        Thread.currentThread().contextClassLoader.getResourceAsStream(path)
            ?: throw IOException("Certificate not found on classpath: $path")

    fun resourceAsText(path: String): String =
        resourceStream(path).bufferedReader().use { it.readText() }

    fun applyDefaultEnvs(s: String): String {
        return s.replace("\$HOME", System.getenv("HOME"))
    }
}