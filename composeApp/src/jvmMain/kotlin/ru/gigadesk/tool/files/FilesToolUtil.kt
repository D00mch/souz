package ru.gigadesk.tool.files

import org.kodein.di.DI
import org.kodein.di.instance
import ru.gigadesk.db.SettingsProvider
import ru.gigadesk.di.mainDiModule
import ru.gigadesk.tool.BadInputException
import java.io.File
import java.io.IOException
import java.io.InputStream

class FilesToolUtil(private val settingsProvider: SettingsProvider) {
    val homeStr: String get() = Companion.homeStr
    val homeDirectory: File get() = Companion.homeDirectory

    /**
     * Generally, we don't want Agent to mess around /
     */
    fun isPathSafe(file: File): Boolean {
        val canonicalPath = file.canonicalFile
        return forbiddenDirectories().none { canonicalPath.startsWith(it) }
    }

    @Throws(BadInputException::class)
    fun requirePathIsSave(file: File) {
        if (!isPathSafe(file)) {
            throw BadInputException("Access denied: File path must be within the home directory")
        }
    }

    fun resourceAsText(path: String): String = Companion.resourceAsText(path)

    fun applyDefaultEnvs(s: String): String {
        if (s.startsWith("~")) {
            return s.replace("~", homeStr)
        }
        if (s == "home") return homeStr
        return s.replace("\$HOME", homeStr)
            .replace("HOME", homeStr)
    }

    private fun forbiddenDirectories(): List<File> {
        return settingsProvider.forbiddenFolders.mapNotNull { raw ->
            val expanded = applyDefaultEnvs(raw).trim()
            if (expanded.isBlank()) return@mapNotNull null
            File(expanded).let { file ->
                if (file.isAbsolute) file else File(homeDirectory, file.path)
            }.canonicalFile
        }.distinct()
    }

    companion object {
        val homeStr: String get() = System.getenv("HOME") ?: System.getProperty("user.home")
        val homeDirectory: File get() = File(homeStr).canonicalFile

        fun resourceStream(path: String): InputStream =
            Thread.currentThread().contextClassLoader.getResourceAsStream(path)
                ?: throw IOException("Certificate not found on classpath: $path")

        fun resourceAsText(path: String): String =
            resourceStream(path).bufferedReader().use { it.readText() }
    }
}

@Suppress("FunctionName")
fun ForbiddenFolder(fixedPath: String) =
    BadInputException("Forbidden directory: $fixedPath. User explicitly restricted this path. Inform him")

fun main() {
    val di = DI.invoke { import(mainDiModule) }
    val filesToolUtil: FilesToolUtil by di.instance()

    val result = filesToolUtil.isPathSafe(File(
        filesToolUtil.applyDefaultEnvs("~/Downloads")
    ))
    println("Safe? $result")
}
