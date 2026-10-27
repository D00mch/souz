package ru.souz.tool.mail

import ru.souz.llms.ToolInvocationMeta

import ru.souz.tool.BadInputException
import ru.souz.tool.FewShotExample
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolRunBashCommand
import ru.souz.tool.ToolSetup

class ToolMailSendNewMessage(private val bash: ToolRunBashCommand, private val sendApproval: MailSendApproval? = null) : ToolSetup<ToolMailSendNewMessage.Input> {
    data class Input(
        @InputParamDescription("Recipient email address (for new email)")
        val recipientAddress: String,

        @InputParamDescription("Subject for the new email")
        val subject: String? = null,

        @InputParamDescription("Body content for new email")
        val content: String? = null,

        @InputParamDescription("Recipient name (for new email)")
        val recipientName: String? = null,

        @InputParamDescription("Request host approval to send the email instead of opening a draft")
        val send: Boolean = false,

        @InputParamDescription("Legacy flag; never authorizes sending. The owner must review and approve through Telegram.")
        val confirmed: Boolean = false,
    )

    override val name: String = "MailSendNewMessage"
    override val description: String = "Create a new email draft with recipient, subject, and body. " +
        "With send=true, prepare an exact preview for host approval through Telegram. " +
        "Return the review command to the user. Model arguments and retries cannot authorize sending."

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

    override fun invoke(input: Input, meta: ToolInvocationMeta): String {
        if (input.recipientAddress.isBlank()) throw BadInputException("recipientAddress is required for new email")
        if (input.send) throw BadInputException("Sending requires the suspending host approval path. Nothing was sent.")
        val subj = input.subject ?: "No Subject"
        val body = input.content ?: ""
        val recName = input.recipientName ?: input.recipientAddress
        return bash.sh(
            MailAppleScriptCommands.sendNewMessageCommand(),
            recName, input.recipientAddress, subj, body,
        )
    }

    override suspend fun suspendInvoke(input: Input, meta: ToolInvocationMeta): String {
        if (!input.send) return invoke(input, meta)
        if (input.recipientAddress.isBlank()) throw BadInputException("recipientAddress is required for new email")
        val approval = sendApproval ?: throw BadInputException("Mail send approval is unavailable. Nothing was sent.")
        return approval.request(
            reply = false,
            args = listOf(input.recipientName ?: input.recipientAddress, input.recipientAddress,
                input.subject ?: "No Subject", input.content ?: ""),
        )
    }
}
