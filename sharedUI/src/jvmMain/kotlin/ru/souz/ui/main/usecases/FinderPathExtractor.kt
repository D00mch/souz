package ru.souz.ui.main.usecases

import ru.souz.tool.files.FilesToolUtil
import ru.souz.ui.common.FinderService
import ru.souz.ui.main.FinderPathItem
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class FinderPathExtractor(
    private val filesToolUtil: FilesToolUtil,
) {
    private val quotedPathPattern = Regex("""["']((?:~/|/|${'$'}HOME/)[^"'\r\n]+)["']""")
    private val markdownLinkPathPattern = Regex("""\[[^\]]+]\(((?:~/|/|${'$'}HOME/)[^)]+)\)""")
    private val inlineCodePathPattern = Regex("""`((?:~/|/|${'$'}HOME/)[^`\r\n]+)`""")
    private val rawPathPattern = Regex("""(?<![A-Za-z0-9._~:/-])((?:~/|/|${'$'}HOME/)[^\s`"'<>|]+)""")
    private val lineTailPathPattern = Regex("""(^|\s)((?:~/|/|${'$'}HOME/).+)$""")
    private val trailingChars = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '"', '\'')

    fun extract(content: String): List<FinderPathItem> =
        extractExistingPaths(content).map { path ->
            FinderPathItem(
                path = path,
                displayName = FinderService.displayName(path),
                isDirectory = FinderService.isDirectory(path)
            )
        }

    private fun extractExistingPaths(content: String): List<String> {
        if (content.isBlank()) return emptyList()

        val candidates = LinkedHashSet<String>()

        quotedPathPattern.findAll(content).forEach { candidates += it.groupValues[1] }
        markdownLinkPathPattern.findAll(content).forEach { candidates += it.groupValues[1] }
        inlineCodePathPattern.findAll(content).forEach { candidates += it.groupValues[1] }
        rawPathPattern.findAll(content).forEach { candidates += it.groupValues[1] }

        content.lineSequence().forEach { line ->
            lineTailPathPattern.find(line)
                ?.groupValues
                ?.getOrNull(2)
                ?.takeIf { it.isNotBlank() }
                ?.let { candidates += it }
        }

        return candidates
            .asSequence()
            .mapNotNull(::resolveExistingPath)
            .distinctBy { it.lowercase() }
            .toList()
    }

    private fun resolveExistingPath(rawCandidate: String): String? {
        val normalizedCandidate = decodePathCandidate(rawCandidate)
            .trim()
            .removeSurrounding("`")
            .let(filesToolUtil::applyDefaultEnvs)
            .let(::trimPathCandidate)
        if (normalizedCandidate.isBlank()) return null

        val attempts = LinkedHashSet<String>()
        attempts += normalizedCandidate

        if (normalizedCandidate.contains(' ')) {
            var trimmed = normalizedCandidate
            while (trimmed.contains(' ')) {
                trimmed = trimmed.substringBeforeLast(' ').trimEnd()
                if (trimmed.isBlank()) break
                attempts += trimPathCandidate(trimmed)
            }
        }

        return attempts
            .asSequence()
            .mapNotNull { FinderService.normalizePath(it) }
            .filter { it != "/" && File(it).exists() }
            .maxByOrNull { it.length }
    }

    private fun trimPathCandidate(candidate: String): String {
        val trimmed = candidate.trim()
        if (trimmed.isEmpty()) return trimmed
        return if (trimmed.length > 1) trimmed.trimEnd { it in trailingChars } else trimmed
    }

    private fun decodePathCandidate(candidate: String): String {
        val unescapedSpaces = candidate.replace("\\ ", " ")
        if (!unescapedSpaces.contains('%')) return unescapedSpaces

        val encodedPlusSafe = unescapedSpaces.replace("+", "%2B")
        return runCatching {
            URLDecoder.decode(encodedPlusSafe, StandardCharsets.UTF_8)
        }.getOrDefault(unescapedSpaces)
    }
}
