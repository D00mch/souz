package ru.gigadesk.tool.mail

import ru.gigadesk.tool.BadInputException
import ru.gigadesk.tool.FewShotExample
import ru.gigadesk.tool.InputParamDescription
import ru.gigadesk.tool.ReturnParameters
import ru.gigadesk.tool.ReturnProperty
import ru.gigadesk.tool.ToolRunBashCommand
import ru.gigadesk.tool.ToolSetup

class ToolMailReadMessage(private val bash: ToolRunBashCommand) : ToolSetup<ToolMailReadMessage.Input> {
    data class Input(
        @InputParamDescription("The unique ID of the message (required for read)")
        val messageId: Int,
    )

    override val name: String = "MailReadMessage"
    override val description: String = "Read a specific message by its ID."

    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Прочитай письмо от Артура",
            params = mapOf("messageId" to 45203)
        ),
    )

    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Mail operation result")
        )
    )

    override fun invoke(input: Input): String {
        if (input.messageId <= 0) throw BadInputException("messageId must be a positive integer")
        return bash.sh(MailAppleScriptCommands.readMessageCommand(input.messageId))
    }
}
