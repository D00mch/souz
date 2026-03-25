package ru.souz.tool.mail

import ru.souz.tool.BadInputException
import ru.souz.tool.FewShotExample
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolActionDescriptor
import ru.souz.tool.ToolActionKind
import ru.souz.tool.ToolActionValueFormatter
import ru.souz.tool.ToolRunBashCommand
import ru.souz.tool.ToolSetup

class ToolMailSendNewMessage(private val bash: ToolRunBashCommand) : ToolSetup<ToolMailSendNewMessage.Input> {
    data class Input(
        @InputParamDescription("Recipient email address (for new email)")
        val recipientAddress: String,

        @InputParamDescription("Subject for the new email")
        val subject: String? = null,

        @InputParamDescription("Body content for new email")
        val content: String? = null,

        @InputParamDescription("Recipient name (for new email)")
        val recipientName: String? = null,
    )

    override val name: String = "MailSendNewMessage"
    override val description: String = "Create a new email draft with recipient, subject, and body."

    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Напиши письмо Ивану (ivan@example.com) с темой 'Отчет' и текстом 'Привет, вот отчет'",
            params = mapOf(
                "recipientAddress" to "ivan@example.com",
                "recipientName" to "Иван",
                "subject" to "Отчет",
                "content" to "Привет, вот отчет",
            )
        ),
    )

    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Mail operation result")
        )
    )

    override fun describeAction(input: Input): ToolActionDescriptor? =
        ToolActionKind.SEND_MAIL.textAction(input.recipientName ?: input.recipientAddress)

    override fun invoke(input: Input): String {
        if (input.recipientAddress.isBlank()) throw BadInputException("recipientAddress is required for new email")
        val subj = input.subject ?: "No Subject"
        val body = input.content ?: ""
        val recName = input.recipientName ?: input.recipientAddress
        return bash.sh(MailAppleScriptCommands.sendNewMessageCommand(recName, input.recipientAddress, subj, body))
    }
}
