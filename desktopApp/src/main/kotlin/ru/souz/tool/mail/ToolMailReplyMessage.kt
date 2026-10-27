package ru.souz.tool.mail

import ru.souz.llms.ToolInvocationMeta

import ru.souz.tool.BadInputException
import ru.souz.tool.FewShotExample
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolRunBashCommand
import ru.souz.tool.ToolSetup

class ToolMailReplyMessage(private val bash: ToolRunBashCommand) : ToolSetup<ToolMailReplyMessage.Input> {
    data class Input(
        @InputParamDescription("The unique ID of the message (required for reply)")
        val messageId: Int,

        @InputParamDescription("Body content for reply")
        val content: String? = null,

        @InputParamDescription("Send the reply instead of opening a draft; requires explicit user confirmation")
        val send: Boolean = false,

        @InputParamDescription("Set true only after showing the original message recipient and reply body and receiving explicit user approval to send")
        val confirmed: Boolean = false,
    )

    override val name: String = "MailReplyMessage"
    override val description: String = "Create a reply draft for a specific message by its ID. " +
        "To send without opening Mail, read the original message and ask the user to approve the recipient and reply body first, " +
        "then call with send=true and confirmed=true. Never infer confirmation from the initial send request."

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
        if (input.send && !input.confirmed) {
            throw BadInputException("Show the original message recipient and reply body, ask the user to confirm sending, " +
                "then repeat with send=true and confirmed=true only after explicit approval. Nothing was sent.")
        }
        val replyContent = input.content ?: ""
        return bash.sh(MailAppleScriptCommands.replyMessageCommand(input.send), input.messageId.toString(), replyContent)
    }

    override suspend fun suspendInvoke(input: Input, meta: ToolInvocationMeta): String = invoke(input, meta)
}

fun main() {
    val result = ToolMailReplyMessage(ToolRunBashCommand).invoke(ToolMailReplyMessage.Input(1382, "Test"), ToolInvocationMeta.localDefault())
    println(result)
}
