package ru.gigadesk.tool.mail

import ru.gigadesk.tool.FewShotExample
import ru.gigadesk.tool.InputParamDescription
import ru.gigadesk.tool.ReturnParameters
import ru.gigadesk.tool.ReturnProperty
import ru.gigadesk.tool.ToolRunBashCommand
import ru.gigadesk.tool.ToolSetup

class ToolMailListMessages(private val bash: ToolRunBashCommand) : ToolSetup<ToolMailListMessages.Input> {
    data class Input(
        @InputParamDescription("Number of messages to list (default 10)")
        val count: Int? = 10,
    )

    override val name: String = "MailListMessages"
    override val description: String = "List the latest messages in the Inbox."

    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Перечисли последние пять писем",
            params = mapOf("count" to 5)
        ),
    )

    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Mail operation result")
        )
    )

    override fun invoke(input: Input): String {
        return bash.sh(MailAppleScriptCommands.listMessagesCommand(input.count ?: 10))
    }
}
