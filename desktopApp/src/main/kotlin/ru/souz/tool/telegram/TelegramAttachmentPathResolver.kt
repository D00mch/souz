package ru.souz.tool.telegram

import ru.souz.tool.files.FilesToolUtil

internal object TelegramAttachmentPathResolver {

    data class ResolvedInput(
        val text: String,
        val attachmentPath: String?,
    )

    fun resolveInput(text: String, explicitPath: String?): ResolvedInput {
        val resolvedAttachmentPath = resolveAttachmentPath(explicitPath, text)
        val messageText = stripAttachmentPathLines(text, resolvedAttachmentPath)
        return ResolvedInput(
            text = messageText,
            attachmentPath = resolvedAttachmentPath,
        )
    }

    fun resolveAttachmentPath(explicitPath: String?, text: String): String? {
        val candidates = buildList {
            explicitPath?.let(::add)
            addAll(extractLineCandidates(text).asReversed())
        }
        for (candidate in candidates) {
            val normalized = FilesToolUtil.normalizeExistingFilePath(candidate) ?: continue
            return normalized
        }
        return null
    }

    fun stripAttachmentPathLines(text: String, resolvedAttachmentPath: String?): String {
        if (text.isBlank() || resolvedAttachmentPath == null) return text.trim()
        return text.lineSequence()
            .filterNot { line ->
                FilesToolUtil.normalizeExistingFilePath(line)?.equals(resolvedAttachmentPath, ignoreCase = true) == true
            }
            .joinToString("\n")
            .trim()
    }

    private fun extractLineCandidates(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        return text.lineSequence()
            .map { line -> line.trim().removePrefix("- ").removePrefix("* ").removePrefix("• ").trim() }
            .filter { it.isNotBlank() }
            .toList()
    }
}
