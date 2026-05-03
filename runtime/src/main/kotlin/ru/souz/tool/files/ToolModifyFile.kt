package ru.souz.tool.files

import org.slf4j.LoggerFactory
import ru.souz.llms.ToolInvocationMeta
import ru.souz.tool.*

class ToolModifyFile(
    private val filesToolUtil: FilesToolUtil,
    private val permissionBroker: DeferredToolModifyPermissionBroker? = null,
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

    override suspend fun suspendInvoke(input: Input, meta: ToolInvocationMeta): String {
        if (permissionBroker?.shouldStageEdits() == true) {
            permissionBroker.stageEdit(input)
            return "Staged, not yet applied"
        }
        return invoke(input, meta)
    }

    override fun invoke(input: Input, meta: ToolInvocationMeta): String {
        val file = filesToolUtil.resolveSafeExistingFile(input.path)
        val editableTextFile = filesToolUtil.readEditableUtf8TextFile(file)
        val preparedEdit = ToolModifyFilePlanner.prepareEdit(input, editableTextFile, filesToolUtil)
        applyPreparedEdit(preparedEdit)
        return "OK"
    }

    private fun applyPreparedEdit(preparedEdit: ToolModifyPreparedEdit) {
        val currentFile = filesToolUtil.readEditableUtf8TextFile(filesToolUtil.resolveSafeExistingFile(preparedEdit.path))
        if (currentFile.rawText != preparedEdit.originalRawText) {
            throw BadInputException("File changed after preview generation. Read it again and retry.")
        }
        filesToolUtil.writeUtf8TextFileAtomically(
            filesToolUtil.resolvePath(preparedEdit.path),
            preparedEdit.updatedRawText,
            l,
        )
    }
}
