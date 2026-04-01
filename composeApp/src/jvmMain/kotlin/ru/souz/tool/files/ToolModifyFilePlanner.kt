package ru.souz.tool.files

import com.github.difflib.DiffUtils
import com.github.difflib.UnifiedDiffUtils
import ru.souz.tool.BadInputException
import java.io.File

internal data class ToolModifyPreparedEdit(
    val file: File,
    val originalRawText: String,
    val originalNormalizedText: String,
    val updatedRawText: String,
    val updatedNormalizedText: String,
    val patchPreview: String,
)

internal object ToolModifyFilePlanner {

    fun prepareEdit(
        input: ToolModifyFile.Input,
        editableTextFile: FilesToolUtil.EditableTextFile,
        filesToolUtil: FilesToolUtil,
    ): ToolModifyPreparedEdit {
        if (input.path.isBlank()) throw BadInputException("Invalid input parameters")

        val normalizedOldString = filesToolUtil.normalizeLineEndings(input.oldString)
        val normalizedNewString = filesToolUtil.normalizeLineEndings(input.newString)

        if (normalizedOldString.isEmpty()) {
            throw BadInputException("oldString must not be empty. Use NewFile to create files.")
        }
        if (normalizedOldString == normalizedNewString) {
            throw BadInputException("No changes to make: oldString and newString are exactly the same.")
        }

        val matchStarts = findMatchStarts(editableTextFile.normalizedText, normalizedOldString)
        if (matchStarts.isEmpty()) {
            throw BadInputException("String to replace not found in file.")
        }
        if (matchStarts.size > 1 && !input.replaceAll) {
            throw BadInputException(
                "Found ${matchStarts.size} matches of oldString, but replaceAll is false. " +
                    "Provide more context or set replaceAll to true."
            )
        }

        val selectedMatchStarts = if (input.replaceAll) matchStarts else listOf(matchStarts.first())
        val updatedNormalizedText = rebuildText(
            sourceText = editableTextFile.normalizedText,
            matchStarts = selectedMatchStarts,
            matchLength = normalizedOldString.length,
            replacement = normalizedNewString,
        )
        if (updatedNormalizedText == editableTextFile.normalizedText) {
            throw BadInputException("String replacement produced no changes.")
        }

        return ToolModifyPreparedEdit(
            file = editableTextFile.file,
            originalRawText = editableTextFile.rawText,
            originalNormalizedText = editableTextFile.normalizedText,
            updatedRawText = rebuildRawText(
                sourceRawText = editableTextFile.rawText,
                rawOffsets = editableTextFile.normalizedTextIndex.rawOffsets,
                matchStarts = selectedMatchStarts,
                matchLength = normalizedOldString.length,
                normalizedReplacement = normalizedNewString,
                preferredLineSeparator = editableTextFile.preferredLineSeparator,
            ),
            updatedNormalizedText = updatedNormalizedText,
            patchPreview = createPatchPreview(
                file = editableTextFile.file,
                originalNormalizedText = editableTextFile.normalizedText,
                updatedNormalizedText = updatedNormalizedText,
            ),
        )
    }

    fun createPatchPreview(
        file: File,
        originalNormalizedText: String,
        updatedNormalizedText: String,
    ): String {
        val originalLines = originalNormalizedText.toDiffLines()
        val updatedLines = updatedNormalizedText.toDiffLines()
        val patch = DiffUtils.diff(originalLines, updatedLines)
        return UnifiedDiffUtils.generateUnifiedDiff(
            "a/${file.name}",
            "b/${file.name}",
            originalLines,
            patch,
            3,
        ).joinToString("\n")
    }

    private fun findMatchStarts(text: String, search: String): List<Int> {
        val starts = ArrayList<Int>()
        var startIndex = 0
        while (true) {
            val foundIndex = text.indexOf(search, startIndex)
            if (foundIndex < 0) return starts
            starts += foundIndex
            startIndex = foundIndex + search.length
        }
    }

    private fun rebuildText(
        sourceText: String,
        matchStarts: List<Int>,
        matchLength: Int,
        replacement: String,
    ): String {
        val out = StringBuilder(sourceText.length)
        var cursor = 0
        matchStarts.forEach { matchStart ->
            out.append(sourceText, cursor, matchStart)
            out.append(replacement)
            cursor = matchStart + matchLength
        }
        out.append(sourceText, cursor, sourceText.length)
        return out.toString()
    }

    private fun rebuildRawText(
        sourceRawText: String,
        rawOffsets: IntArray,
        matchStarts: List<Int>,
        matchLength: Int,
        normalizedReplacement: String,
        preferredLineSeparator: String,
    ): String {
        val out = StringBuilder(sourceRawText.length)
        var rawCursor = 0
        matchStarts.forEach { matchStart ->
            val matchEnd = matchStart + matchLength
            val rawStart = rawOffsets[matchStart]
            val rawEnd = rawOffsets[matchEnd]
            out.append(sourceRawText, rawCursor, rawStart)
            out.append(
                restoreReplacementLineEndings(
                    normalizedText = normalizedReplacement,
                    rawTemplate = sourceRawText.substring(rawStart, rawEnd),
                    preferredLineSeparator = preferredLineSeparator,
                )
            )
            rawCursor = rawEnd
        }
        out.append(sourceRawText, rawCursor, sourceRawText.length)
        return out.toString()
    }

    private fun restoreReplacementLineEndings(
        normalizedText: String,
        rawTemplate: String,
        preferredLineSeparator: String,
    ): String {
        if (!normalizedText.contains('\n')) return normalizedText

        val templateSeparators = extractLineSeparators(rawTemplate)
        val fallbackSeparator = templateSeparators.lastOrNull() ?: preferredLineSeparator
        var separatorIndex = 0
        val out = StringBuilder(normalizedText.length + templateSeparators.sumOf(String::length))

        normalizedText.forEach { ch ->
            if (ch == '\n') {
                val separator = if (separatorIndex < templateSeparators.size) {
                    templateSeparators[separatorIndex++]
                } else {
                    fallbackSeparator
                }
                out.append(separator)
            } else {
                out.append(ch)
            }
        }
        return out.toString()
    }

    private fun extractLineSeparators(text: String): List<String> {
        val separators = ArrayList<String>()
        var index = 0
        while (index < text.length) {
            when (text[index]) {
                '\r' -> {
                    if (index + 1 < text.length && text[index + 1] == '\n') {
                        separators += "\r\n"
                        index += 2
                    } else {
                        separators += "\r"
                        index += 1
                    }
                }

                '\n' -> {
                    separators += "\n"
                    index += 1
                }

                else -> index += 1
            }
        }
        return separators
    }

    private fun String.toDiffLines(): List<String> =
        split("\n", ignoreCase = false, limit = Int.MAX_VALUE)
}
