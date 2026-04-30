package ru.souz.tool.textReplace

import ru.souz.tool.FewShotExample
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolRunBashCommand
import ru.souz.tool.ToolSetup
import ru.souz.tool.apple
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.util.UUID

class ToolTextUnderSelection(
    private val bash: ToolRunBashCommand,
    private val toolGetClipboard: ToolGetClipboard,
) : ToolSetup<ToolTextUnderSelection.Input> {
    object Input

    override val name: String = "TextUnderSelection"
    override val description: String = "A tool to provide text user selected"
    override val fewShotExamples = listOf(FewShotExample(request = "Перепиши выбранный текст", params = emptyMap()))
    override val returnParameters: ReturnParameters = ReturnParameters(
        properties = mapOf("result" to ReturnProperty("string", "The selected text in quotes")),
    )

    override fun invoke(input: Input): String {
        return getDataFromSelectionWithKeys()
    }

    private fun getDataFromSelectionWithKeys(): String {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val previousContent: Transferable? = runCatching { clipboard.getContents(null) }.getOrNull()
        val marker = "SOUZ_SELECTION_MARKER_${UUID.randomUUID()}"
        runCatching { clipboard.setContents(StringSelection(marker), null) }

        return try {
            bash.apple(SCRIPT)

            repeat(COPY_RETRY_ATTEMPTS) {
                Thread.sleep(COPY_RETRY_DELAY_MS)
                val current = toolGetClipboard.invoke(ToolGetClipboard.Input)
                if (current == NO_SELECTION_ERROR) return@repeat
                if (current != marker) return current
            }

            NO_SELECTION_ERROR
        } finally {
            if (previousContent != null) {
                runCatching { clipboard.setContents(previousContent, null) }
            }
        }
    }

    private companion object {
        private const val NO_SELECTION_ERROR = "Error: \"Nothing in selection\""
        private const val COPY_RETRY_ATTEMPTS = 8
        private const val COPY_RETRY_DELAY_MS = 30L
    }
}

private const val SCRIPT = """tell application "System Events"
	keystroke "c" using command down
end tell"""
