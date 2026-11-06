package ru.souz.tool.textReplace

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import ru.souz.llms.ToolInvocationMeta

import ru.souz.service.keys.MrRobot
import ru.souz.tool.*
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable

class ToolTextReplace(
    private val bash: ToolRunBashCommand
) : ToolSetup<ToolTextReplace.Input> {

    data class Input(
        @InputParamDescription("The newText that will replace the text under selection")
        val newText: String
    )

    override val name: String = "TextReplace"
    override val description: String = "Replace the text that is in selection with the newText"

    override val fewShotExamples: List<FewShotExample> = listOf(
        FewShotExample(
            request = "Перепиши выделенный текст",
            params = mapOf("newText" to "Новый текст тут")
        )
    )

    override val returnParameters: ReturnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Ok on success, or Error message of failure")
        )
    )

    override fun invoke(input: Input, meta: ToolInvocationMeta): String = ClipboardTransactions.run {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val previousContent: Transferable? = runCatching { clipboard.getContents(null) }.getOrNull()

        try {
            runCatching { clipboard.setContents(StringSelection(input.newText), null) }
                .onFailure { MrRobot.clipboardPut(input.newText) }
            try {
                bash.apple(SCRIPT)
            } finally {
                // Dispatch can succeed even if waiting for osascript is interrupted.
                ClipboardTransactions.settle(PASTE_SETTLE_DELAY_MS)
            }
            "ok"
        } finally {
            if (previousContent != null) {
                runCatching { clipboard.setContents(previousContent, null) }
            }
        }
    }

    private companion object {
        private const val PASTE_SETTLE_DELAY_MS = 70L
    }

    override suspend fun suspendInvoke(input: Input, meta: ToolInvocationMeta): String =
        runInterruptible(Dispatchers.IO) { invoke(input, meta) }
}

private const val SCRIPT = """
tell application "System Events"
	keystroke "v" using command down
end tell
        """
