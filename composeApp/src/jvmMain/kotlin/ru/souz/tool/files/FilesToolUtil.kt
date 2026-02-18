package ru.souz.tool.files

import org.kodein.di.DI
import org.kodein.di.instance
import ru.souz.db.SettingsProvider
import ru.souz.di.mainDiModule
import ru.souz.tool.BadInputException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class FilesToolUtil(private val settingsProvider: SettingsProvider) {

    val homeStr: String
        get() = Companion.homeStr

    /**
     * Generally, we don't want Agent to mess around anything out of $HOME and everything user disallowed
     */
    fun isPathSafe(file: File): Boolean {
        val canonicalPath = file.canonicalFile
        return file.canonicalPath.startsWith(homeStr) &&
                forbiddenDirectories().none { canonicalPath.startsWith(it) }
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


    /**
     * Moves file from [sourcePath] to [destinationPath].
     *
     * First we request [StandardCopyOption.ATOMIC_MOVE], which asks the filesystem to make
     * the move atomic (all-or-nothing: readers should see either source or destination state,
     * not a partially moved file). Some filesystems do not support atomic moves for a given
     * source/destination pair; in that case we fall back to a regular move.
     */
    fun moveWithAtomicFallback(sourcePath: Path, destinationPath: Path, logger: org.slf4j.Logger) {
        try {
            Files.move(sourcePath, destinationPath, StandardCopyOption.ATOMIC_MOVE)
        } catch (exception: AtomicMoveNotSupportedException) {
            logger.warn("Failed to make an atomic move", exception)
            Files.move(sourcePath, destinationPath)
        }
    }

    fun forbiddenDirectories(): List<File> {
        return settingsProvider.forbiddenFolders.mapNotNull { raw ->
            val expanded = applyDefaultEnvs(raw).trim()
            if (expanded.isBlank()) return@mapNotNull null
            val resolved = File(expanded).let { file ->
                if (file.isAbsolute) file else File(homeDirectory, file.path)
            }.canonicalFile
            if (!resolved.startsWith(homeDirectory)) return@mapNotNull null
            resolved
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
        filesToolUtil.applyDefaultEnvs("~/")
    ))
    println("Safe? $result")
}
