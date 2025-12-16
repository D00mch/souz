package ru.gigadesk.tool.mail

import ru.gigadesk.tool.FewShotExample
import ru.gigadesk.tool.InputParamDescription
import ru.gigadesk.tool.ReturnParameters
import ru.gigadesk.tool.ReturnProperty
import ru.gigadesk.tool.ToolRunBashCommand
import ru.gigadesk.tool.ToolSetup

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