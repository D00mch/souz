package ru.gigadesk.tool.files

import ru.gigadesk.tool.BadInputException
import java.io.File
import java.io.IOException
import java.io.InputStream

object FilesToolUtil {
    val homeStr: String get() = System.getenv("HOME") ?: System.getProperty("user.home")
    val homeDirectory: File get() = File(homeStr).canonicalFile

    /**
     * Generally, we don't want Agent to mess around /
     */
    fun isPathSafe(file: File): Boolean {
        val canonicalPath = file.canonicalFile
        return canonicalPath.startsWith(homeDirectory)
    }

    @Throws(BadInputException::class)
    fun requirePathIsSave(file: File) {
        if (!isPathSafe(file)) {
            throw BadInputException("Access denied: File path must be within the home directory")
        }
    }

    fun resourceStream(path: String): InputStream =
        Thread.currentThread().contextClassLoader.getResourceAsStream(path)
            ?: throw IOException("Certificate not found on classpath: $path")

    fun resourceAsText(path: String): String =
        resourceStream(path).bufferedReader().use { it.readText() }

    fun applyDefaultEnvs(s: String): String {
        if (s.startsWith("~")) {
            return s.replace("~", homeStr)
        }
        if (s == "home") return homeStr
        return s.replace("\$HOME", homeStr)
            .replace("HOME", homeStr)
    }
}

fun main() {
    println(System.getProperty("user.home") == System.getenv("HOME"))
}