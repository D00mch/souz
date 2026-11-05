package ru.souz.tool.textReplace

import ru.souz.llms.ToolInvocationMeta

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import ru.souz.tool.FewShotExample
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolSetup

class ToolGetClipboard : ToolSetup<ToolGetClipboard.Input> {
    object Input

    override val name: String = "GetClipboard"
    override val description: String = "Returns the data in the clipboard"
    override val fewShotExamples = listOf(FewShotExample(request = "Что в буффере обмена?", params = emptyMap()))
    override val returnParameters: ReturnParameters = ReturnParameters(
        properties = mapOf("result" to ReturnProperty("string", "The text from clipboard in quot")),
    )

    override fun invoke(input: Input, meta: ToolInvocationMeta): String = ClipboardTransactions.run {
        val content = runCatching { Toolkit.getDefaultToolkit().systemClipboard.getContents(null) }
            .onFailure { Thread.sleep(20) }
            .getOrNull()
        val text = if (content?.isDataFlavorSupported(DataFlavor.stringFlavor) == true) {
            runCatching { content.getTransferData(DataFlavor.stringFlavor) as String }.getOrNull()
        } else null
        text ?: "Error: \"Nothing in selection\""
    }

    override suspend fun suspendInvoke(input: Input, meta: ToolInvocationMeta): String =
        runInterruptible(Dispatchers.IO) { invoke(input, meta) }
}
