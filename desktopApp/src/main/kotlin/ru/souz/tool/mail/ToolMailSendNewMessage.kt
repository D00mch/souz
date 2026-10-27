package ru.souz.tool.mail

import ru.souz.llms.ToolInvocationMeta

import ru.souz.tool.BadInputException
import ru.souz.tool.FewShotExample
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
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

        @InputParamDescription("Send the email instead of opening a draft; requires explicit user confirmation")
        val send: Boolean = false,

        @InputParamDescription("Set true only after showing the recipient, subject and body and receiving explicit user approval to send")
        val confirmed: Boolean = false,
    )

    override val name: String = "MailSendNewMessage"
    override val description: String = "Create a new email draft with recipient, subject, and body. " +
        "To send without opening Mail, ask the user to approve the recipient, subject and body first, " +
        "then call with send=true and confirmed=true. Never infer confirmation from the initial send request."

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
        if (input.send && !input.confirmed) {
            throw BadInputException("Show the recipient, subject and body, ask the user to confirm sending, " +
                "then repeat with send=true and confirmed=true only after explicit approval. Nothing was sent.")
        }
        val subj = input.subject ?: "No Subject"
        val body = input.content ?: ""
        val recName = input.recipientName ?: input.recipientAddress
        return bash.sh(
            MailAppleScriptCommands.sendNewMessageCommand(input.send),
            recName, input.recipientAddress, subj, body,
        )
    }

    override suspend fun suspendInvoke(input: Input, meta: ToolInvocationMeta): String = invoke(input, meta)
}
