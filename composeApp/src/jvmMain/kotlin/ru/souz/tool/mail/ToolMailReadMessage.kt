package ru.souz.tool.mail

import ru.souz.tool.BadInputException
import ru.souz.tool.FewShotExample
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolActionDescriptor
import ru.souz.tool.ToolActionKind
import ru.souz.tool.ToolRunBashCommand
import ru.souz.tool.ToolSetup

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

    override fun describeAction(input: Input): ToolActionDescriptor? = ToolActionDescriptor(
        kind = ToolActionKind.READ_MAIL,
    )

    override fun invoke(input: Input): String {
        if (input.messageId <= 0) throw BadInputException("messageId must be a positive integer")
        return bash.sh(MailAppleScriptCommands.readMessageCommand(input.messageId))
    }
}
