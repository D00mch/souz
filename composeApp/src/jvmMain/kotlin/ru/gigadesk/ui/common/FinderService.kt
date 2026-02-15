package ru.gigadesk.ui.common

import java.awt.Desktop
import java.io.File

object FinderService {

    private const val fileSchemePrefix = "file://"

    fun normalizePath(rawPath: String): String? {
        val trimmed = rawPath.trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .removePrefix(fileSchemePrefix)
        if (trimmed.isBlank()) return null

        val expanded = expandHome(trimmed)
        val normalized = runCatching { File(expanded).canonicalPath }
            .getOrElse { File(expanded).absolutePath }
            .replace('\\', '/')

        return if (normalized.length > 1) normalized.trimEnd('/') else normalized
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

    private fun expandHome(path: String): String {
        val home = System.getProperty("user.home")
            ?: System.getenv("HOME")
            ?: return path
        return when {
            path == "~" -> home
            path.startsWith("~/") -> File(home, path.removePrefix("~/")).path
            else -> path
        }
    }

    private fun isMacOs(): Boolean =
        System.getProperty("os.name")
            ?.contains("mac", ignoreCase = true)
            ?: false
}
