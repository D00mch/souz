package ru.gigadesk.tool.mail

import ru.gigadesk.tool.BadInputException
import ru.gigadesk.tool.FewShotExample
import ru.gigadesk.tool.InputParamDescription
import ru.gigadesk.tool.ReturnParameters
import ru.gigadesk.tool.ReturnProperty
import ru.gigadesk.tool.ToolRunBashCommand
import ru.gigadesk.tool.ToolSetup

class ToolMailReplyMessage(private val bash: ToolRunBashCommand) : ToolSetup<ToolMailReplyMessage.Input> {
    data class Input(
        @InputParamDescription("The unique ID of the message (required for reply)")
        val messageId: Int,

        @InputParamDescription("Body content for reply")
        val content: String? = null,
    )

    override val name: String = "MailReplyMessage"
    override val description: String = "Reply to a specific message by its ID."

    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Ответь на письмо Артура: 'Спасибо, получил'",
            params = mapOf("messageId" to 45203, "content" to "Спасибо, получил")
        ),
    )

    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Mail operation result")
        )
    )

    override fun invoke(input: Input): String {
        if (input.messageId <= 0) throw BadInputException("messageId must be a positive integer")
        val replyContent = input.content ?: ""
        return bash.sh(MailAppleScriptCommands.replyMessageCommand(input.messageId, replyContent))
    }
}
