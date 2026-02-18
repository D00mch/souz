package ru.souz.tool.mail

import ru.souz.tool.FewShotExample
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolRunBashCommand
import ru.souz.tool.ToolSetup

class ToolMailUnreadMessagesCount(private val bash: ToolRunBashCommand) : ToolSetup<ToolMailUnreadMessagesCount.Input> {

    data class Input(
        @InputParamDescription("The maximum limit for counting unread messages (e.g., 50)")
        val limit: Int
    )

    override val name: String = "MailUnreadMessagesCount"
    override val description: String = "Get the number of unread emails in the Inbox with a specified limit."

    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Сколько у меня непрочитанных писем?",
            params = mapOf("limit" to 50)
        ),
    )

    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Mail operation result (count of unread messages)")
        )
    )

    override fun invoke(input: Input): String {
        return bash.sh(MailAppleScriptCommands.unreadCountCommand(input.limit))
    }
}