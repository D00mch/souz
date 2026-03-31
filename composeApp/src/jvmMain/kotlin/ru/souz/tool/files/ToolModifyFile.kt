package ru.souz.tool.files

import com.github.difflib.DiffUtils
import com.github.difflib.UnifiedDiffUtils
import org.jetbrains.compose.resources.getString
import org.slf4j.LoggerFactory
import ru.souz.tool.*
import souz.composeapp.generated.resources.Res
import souz.composeapp.generated.resources.*
import java.io.File

class ToolModifyFile(
    private val filesToolUtil: FilesToolUtil,
    private val permissionBroker: ToolPermissionBroker? = null,
) : ToolSetup<ToolModifyFile.Input> {
    private val l = LoggerFactory.getLogger(ToolModifyFile::class.java)

    data class Input(
        @InputParamDescription("The path to the file to be modified")
        val path: String,

        @InputParamDescription(
            "Exact text to replace in the current file. Must match the existing content."
        )
        val oldString: String,

        @InputParamDescription("Replacement text. May be empty to delete the matched text.")
        val newString: String,

        @InputParamDescription("Replace all occurrences of oldString. Default false.")
        val replaceAll: Boolean = false,
    )

    override val name = "EditFile"
    override val description =
        "Modify an existing plain text, code, or config file by replacing exact text. " +
                "Fails when oldString is missing or ambiguous unless replaceAll is true."

    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Замени строку в ~/notes.txt",
            params = mapOf(
                "path" to "~/notes.txt",
                "oldString" to "two",
                "newString" to "TWO",
                "replaceAll" to false,
            )
        )
    )

    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Operation status")
        )
    )

    override suspend fun suspendInvoke(input: Input): String {
        val preparedEdit = prepareEdit(input)
        val result = permissionBroker?.requestPermission(
            getString(Res.string.permission_modify_file),
            linkedMapOf(
                "path" to preparedEdit.file.path,
                "replaceAll" to input.replaceAll.toString(),
                "patch" to preparedEdit.patchPreview,
            )
        )
        if (result is ToolPermissionResult.No) return result.msg
        applyPreparedEdit(preparedEdit)
        return "OK"
    }

    override fun invoke(input: Input): String {
        val preparedEdit = prepareEdit(input)
        applyPreparedEdit(preparedEdit)
        return "OK"
    }

    private data class PreparedEdit(
        val file: File,
        val originalRawText: String,
        val updatedRawText: String,
        val patchPreview: String,
    )

    private fun prepareEdit(input: Input): PreparedEdit {
        if (input.path.isBlank()) throw BadInputException("Invalid input parameters")

        val file = filesToolUtil.resolveSafeExistingFile(input.path)
        val editableTextFile = filesToolUtil.readEditableUtf8TextFile(file)
        val normalizedOldString = filesToolUtil.normalizeLineEndings(input.oldString)
        val normalizedNewString = filesToolUtil.normalizeLineEndings(input.newString)

        if (normalizedOldString.isEmpty()) {
            throw BadInputException("oldString must not be empty. Use NewFile to create files.")
        }
        if (normalizedOldString == normalizedNewString) {
            throw BadInputException("No changes to make: oldString and newString are exactly the same.")
        }

        val matches = countOccurrences(editableTextFile.normalizedText, normalizedOldString)
        if (matches == 0) {
            throw BadInputException("String to replace not found in file.")
        }
        if (matches > 1 && !input.replaceAll) {
            throw BadInputException(
                "Found $matches matches of oldString, but replaceAll is false. " +
                        "Provide more context or set replaceAll to true."
            )
        }

        val updatedNormalizedText = if (input.replaceAll) {
            editableTextFile.normalizedText.replace(normalizedOldString, normalizedNewString)
        } else {
            editableTextFile.normalizedText.replaceFirst(normalizedOldString, normalizedNewString)
        }
        if (updatedNormalizedText == editableTextFile.normalizedText) {
            throw BadInputException("String replacement produced no changes.")
        }

        return PreparedEdit(
            file = file,
            originalRawText = editableTextFile.rawText,
            updatedRawText = filesToolUtil.restoreLineEndings(updatedNormalizedText, editableTextFile.lineSeparator),
            patchPreview = createPatchPreview(
                file = file,
                originalNormalizedText = editableTextFile.normalizedText,
                updatedNormalizedText = updatedNormalizedText,
            ),
        )
    }

    private fun applyPreparedEdit(preparedEdit: PreparedEdit) {
        val currentFile = filesToolUtil.readEditableUtf8TextFile(preparedEdit.file)
        if (currentFile.rawText != preparedEdit.originalRawText) {
            throw BadInputException("File changed after preview generation. Read it again and retry.")
        }
        filesToolUtil.writeUtf8TextFileAtomically(preparedEdit.file, preparedEdit.updatedRawText, l)
    }

    private fun createPatchPreview(
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

    private fun countOccurrences(text: String, search: String): Int {
        var count = 0
        var startIndex = 0
        while (true) {
            val foundIndex = text.indexOf(search, startIndex)
            if (foundIndex < 0) return count
            count += 1
            startIndex = foundIndex + search.length
        }
    }

    private fun String.toDiffLines(): List<String> =
        split("\n", ignoreCase = false, limit = Int.MAX_VALUE)
}
