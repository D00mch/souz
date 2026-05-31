package ru.souz.runtime.sandbox

object SandboxUserFacingPathNormalizer {
    fun normalize(rawPath: String, runtimePaths: SandboxRuntimePaths): String {
        val cleaned = clean(rawPath)
        val home = normalizeAbsolutePath(runtimePaths.homePath)
        val expanded = when {
            cleaned.isBlank() -> "/"
            cleaned == "~" || cleaned == "\$HOME" || cleaned == "home" -> home
            cleaned.startsWith("~/") -> joinPath(home, cleaned.removePrefix("~/"))
            cleaned.startsWith("\$HOME/") -> joinPath(home, cleaned.removePrefix("\$HOME/"))
            cleaned.startsWith("/") -> cleaned
            else -> joinPath(home, cleaned)
        }
        return normalizeAbsolutePath(expanded)
    }

    private fun clean(rawPath: String): String {
        var cleaned = rawPath.trim()
        while (
            cleaned.length >= 2 &&
            cleaned.first() == cleaned.last() &&
            cleaned.first() in SURROUNDING_QUOTES
        ) {
            cleaned = cleaned.substring(1, cleaned.lastIndex).trim()
        }
        return cleaned
            .removePrefix("file://")
            .replace('\\', '/')
            .trim()
    }

    private fun joinPath(root: String, suffix: String): String =
        if (suffix.isBlank()) {
            root
        } else {
            "${root.trimEnd('/')}/${suffix.trimStart('/')}"
        }

    private fun normalizeAbsolutePath(path: String): String {
        val stack = ArrayDeque<String>()
        path.split('/').forEach { segment ->
            when {
                segment.isBlank() || segment == "." -> Unit
                segment == ".." -> if (stack.isNotEmpty()) stack.removeLast()
                else -> stack.addLast(segment)
            }
        }
        return if (stack.isEmpty()) "/" else stack.joinToString(prefix = "/", separator = "/")
    }

    private val SURROUNDING_QUOTES = setOf('`', '"', '\'')
}
