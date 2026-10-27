package ru.souz.tool.mail

import ru.souz.llms.ToolInvocationMeta

import ru.souz.tool.BadInputException
import ru.souz.tool.FewShotExample
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolRunBashCommand
import ru.souz.tool.ToolSetup

class ToolMailReplyMessage(private val bash: ToolRunBashCommand, private val sendApproval: MailSendApproval? = null) : ToolSetup<ToolMailReplyMessage.Input> {
    data class Input(
        @InputParamDescription("The unique ID of the message (required for reply)")
        val messageId: Int,

        @InputParamDescription("Body content for reply")
        val content: String? = null,

        @InputParamDescription("Request host approval to send the reply instead of opening a draft")
        val send: Boolean = false,

        @InputParamDescription("Legacy flag; never authorizes sending. The owner must review and approve through Telegram.")
        val confirmed: Boolean = false,
    )

    override val name: String = "MailReplyMessage"
    override val description: String = "Create a reply draft for a specific message by its ID. " +
        "With send=true, prepare an exact preview for host approval through Telegram. " +
        "Return the review command to the user. Model arguments and retries cannot authorize sending."

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

    override fun invoke(input: Input, meta: ToolInvocationMeta): String {
        if (input.messageId <= 0) throw BadInputException("messageId must be a positive integer")
        if (input.send) throw BadInputException("Sending requires the suspending host approval path. Nothing was sent.")
        val replyContent = input.content ?: ""
        return bash.sh(MailAppleScriptCommands.replyMessageCommand(), input.messageId.toString(), replyContent)
    }

    override suspend fun suspendInvoke(input: Input, meta: ToolInvocationMeta): String {
        if (!input.send) return invoke(input, meta)
        if (input.messageId <= 0) throw BadInputException("messageId must be a positive integer")
        val approval = sendApproval ?: throw BadInputException("Mail send approval is unavailable. Nothing was sent.")
        return approval.request(reply = true, args = listOf(input.messageId.toString(), input.content ?: ""))
    }
}

fun main() {
    val result = ToolMailReplyMessage(ToolRunBashCommand).invoke(ToolMailReplyMessage.Input(1382, "Test"), ToolInvocationMeta.localDefault())
    println(result)
}
