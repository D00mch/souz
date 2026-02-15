package ru.gigadesk.ui.common

import java.awt.Desktop
import java.io.File

object FinderService {

    private const val fileSchemePrefix = "file://"
    private val windowsRootPathPattern = Regex("""^[A-Za-z]:/$""")

    fun normalizePath(rawPath: String): String? {
        val trimmed = rawPath.trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .let(::stripFileSchemePrefix)
        if (trimmed.isBlank()) return null

        val expanded = expandHomeAliases(trimmed)
        val normalizedFile = resolveRelativeToHome(expanded)
        val normalized = runCatching { normalizedFile.canonicalPath }
            .getOrElse { normalizedFile.absolutePath }
            .replace('\\', '/')

        return trimTrailingSeparator(normalized)
    }

    fun displayName(rawPath: String): String {
        val normalized = normalizePath(rawPath) ?: rawPath.trim()
        val visiblePath = if (normalized.length > 1) normalized.trimEnd('/') else normalized
        val name = File(visiblePath).name
        return name.ifBlank { visiblePath }
    }

    fun isDirectory(rawPath: String): Boolean {
        val normalized = normalizePath(rawPath) ?: return rawPath.trimEnd().endsWith("/")
        val target = File(normalized)
        return if (target.exists()) target.isDirectory else rawPath.trimEnd().endsWith("/")
    }

    fun openInFinder(rawPath: String): Result<Unit> = runCatching {
        val normalized = normalizePath(rawPath)
            ?: error("Пустой путь")

        val target = File(normalized)
        require(target.exists()) { "Путь не найден: $normalized" }

        if (isMacOs()) {
            val command = if (target.isDirectory) {
                listOf("open", target.absolutePath)
            } else {
                listOf("open", "-R", target.absolutePath)
            }
            ProcessBuilder(command).start()
            return@runCatching
        }

        require(Desktop.isDesktopSupported()) { "Desktop API не поддерживается" }
        val desktop = Desktop.getDesktop()
        require(desktop.isSupported(Desktop.Action.OPEN)) { "Открытие путей не поддерживается" }

        val openTarget = if (target.isDirectory) target else target.parentFile ?: target
        desktop.open(openTarget)
    }

    private fun expandHomeAliases(path: String): String {
        val home = currentHomePath() ?: return path
        return when {
            path == "~" -> home
            path.equals("home", ignoreCase = true) -> home
            path.startsWith("~/") -> File(home, path.removePrefix("~/")).path
            path.startsWith("home/", ignoreCase = true) -> File(home, path.substring(5)).path
            path == "\$HOME" -> home
            path.startsWith("\$HOME/") -> File(home, path.removePrefix("\$HOME/")).path
            else -> path
        }
    }

    private fun resolveRelativeToHome(path: String): File {
        val file = File(path)
        if (file.isAbsolute) return file

        val home = currentHomePath() ?: return file
        return File(home, file.path)
    }

    private fun stripFileSchemePrefix(path: String): String {
        return if (path.startsWith(fileSchemePrefix, ignoreCase = true)) {
            path.substring(fileSchemePrefix.length)
        } else {
            path
        }
    }

    private fun trimTrailingSeparator(path: String): String {
        if (path.length <= 1) return path
        if (windowsRootPathPattern.matches(path)) return path
        return path.trimEnd('/')
    }

    private fun currentHomePath(): String? =
        System.getProperty("user.home") ?: System.getenv("HOME")

    private fun isMacOs(): Boolean =
        System.getProperty("os.name")
            ?.contains("mac", ignoreCase = true)
            ?: false
}
