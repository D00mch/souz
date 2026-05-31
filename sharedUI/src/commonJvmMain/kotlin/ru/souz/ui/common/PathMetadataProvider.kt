package ru.souz.ui.common

import ru.souz.runtime.files.FilesToolUtil
import java.io.File

interface PathMetadataProvider {
    fun normalizePath(rawPath: String): String?
    fun displayName(rawPath: String): String
    fun isDirectory(rawPath: String): Boolean
    fun exists(rawPath: String): Boolean
}

class FileSystemPathMetadataProvider : PathMetadataProvider {
    private val fileSchemePrefix = "file://"
    private val windowsRootPathPattern = Regex("""^[A-Za-z]:/$""")

    override fun normalizePath(rawPath: String): String? {
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

    override fun displayName(rawPath: String): String {
        val normalized = normalizePath(rawPath) ?: rawPath.trim()
        val visiblePath = if (normalized.length > 1) normalized.trimEnd('/') else normalized
        val name = File(visiblePath).name
        return name.ifBlank { visiblePath }
    }

    override fun isDirectory(rawPath: String): Boolean {
        val normalized = normalizePath(rawPath) ?: return rawPath.trimEnd().endsWith("/")
        val target = File(normalized)
        return if (target.exists()) target.isDirectory else rawPath.trimEnd().endsWith("/")
    }

    override fun exists(rawPath: String): Boolean {
        val normalized = normalizePath(rawPath) ?: return false
        return File(normalized).exists()
    }

    private fun expandHomeAliases(path: String): String {
        val home = FilesToolUtil.homeStr
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

        return File(FilesToolUtil.homeStr, file.path)
    }

    private fun stripFileSchemePrefix(path: String): String =
        if (path.startsWith(fileSchemePrefix, ignoreCase = true)) {
            path.substring(fileSchemePrefix.length)
        } else {
            path
        }

    private fun trimTrailingSeparator(path: String): String {
        if (path.length <= 1) return path
        if (windowsRootPathPattern.matches(path)) return path
        return path.trimEnd('/')
    }
}
