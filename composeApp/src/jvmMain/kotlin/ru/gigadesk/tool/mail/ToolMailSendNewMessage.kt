package ru.gigadesk.tool.mail

import ru.gigadesk.tool.BadInputException
import ru.gigadesk.tool.FewShotExample
import ru.gigadesk.tool.InputParamDescription
import ru.gigadesk.tool.ReturnParameters
import ru.gigadesk.tool.ReturnProperty
import ru.gigadesk.tool.ToolRunBashCommand
import ru.gigadesk.tool.ToolSetup

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

    override fun invoke(input: Input): String {
        if (input.recipientAddress.isBlank()) throw BadInputException("recipientAddress is required for new email")
        val subj = input.subject ?: "No Subject"
        val body = input.content ?: ""
        val recName = input.recipientName ?: input.recipientAddress
        return bash.sh(MailAppleScriptCommands.sendNewMessageCommand(recName, input.recipientAddress, subj, body))
    }
}
