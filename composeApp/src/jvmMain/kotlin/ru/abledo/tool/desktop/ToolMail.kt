package ru.abledo.tool.desktop

import ru.abledo.tool.*

class ToolMail(private val bash: ToolRunBashCommand) : ToolSetup<ToolMail.Input> {

    enum class MailAction {
        unread_count,
        list_messages,
        read_message,
        reply_message,
        send_new_message
    }

    data class Input(
        @InputParamDescription("Action to perform with Mail app")
        val action: MailAction,

        @InputParamDescription("Number of messages to list (default 10)")
        val count: Int? = 10,

        @InputParamDescription("The unique ID of the message (required for read and reply)")
        val messageId: Int? = null,

        @InputParamDescription("Subject for the new email")
        val subject: String? = null,

        @InputParamDescription("Body content for new email or reply")
        val content: String? = null,

        @InputParamDescription("Recipient email address (for new email)")
        val recipientAddress: String? = null,

        @InputParamDescription("Recipient name (for new email)")
        val recipientName: String? = null
    )

    override val name: String = "MailAppTool"
    override val description: String = "Interact with Mails: unread count, list messages, read message, reply message, send new message"

    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Сколько у меня непрочитанных писем?",
            params = mapOf("action" to MailAction.unread_count)
        ),
        FewShotExample(
            request = "Перечисли последние пять писем",
            params = mapOf("action" to MailAction.list_messages, "count" to 5)
        ),
        FewShotExample(
            request = "Прочитай письмо от Артура",
            params = mapOf("action" to MailAction.read_message, "messageId" to 45203)
        ),
        FewShotExample(
            request = "Ответь на письмо Артура: 'Спасибо, получил'",
            params = mapOf("action" to MailAction.reply_message, "messageId" to 45203, "content" to "Спасибо, получил")
        ),
        FewShotExample(
            request = "Напиши письмо Ивану (ivan@example.com) с темой 'Отчет' и текстом 'Привет, вот отчет'",
            params = mapOf(
                "action" to MailAction.send_new_message,
                "recipientAddress" to "ivan@example.com",
                "recipientName" to "Иван",
                "subject" to "Отчет",
                "content" to "Привет, вот отчет"
            )
        )
    )

    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Output from the Mail application (content, status, or error)")
        )
    )

    override fun invoke(input: Input): String {
        return when (input.action) {
            MailAction.unread_count -> bash.sh(unreadCountCommand())

            MailAction.list_messages -> bash.sh(listMessagesCommand(input.count ?: 10))

            MailAction.read_message -> {
                if (input.messageId == null) return "Error: messageId is required to read a message."
                bash.sh(readMessageCommand(input.messageId))
            }

            MailAction.reply_message -> {
                if (input.messageId == null) return "Error: messageId is required to reply."
                val replyContent = input.content ?: ""
                bash.sh(replyMessageCommand(input.messageId, replyContent))
            }

            MailAction.send_new_message -> {
                if (input.recipientAddress == null) return "Error: recipientAddress is required for new email."
                val subj = input.subject ?: "No Subject"
                val body = input.content ?: ""
                val recName = input.recipientName ?: input.recipientAddress
                bash.sh(sendNewMessageCommand(recName, input.recipientAddress, subj, body))
            }
        }
    }

    private fun unreadCountCommand(): String = """
osascript <<'EOF'
tell application "Mail"
    set unreadCount to (count of (messages of inbox whose read status is false))
    return unreadCount
end tell
EOF
    """.trimIndent()

    private fun listMessagesCommand(limit: Int): String = """
osascript <<'EOF'
tell application "Mail"
    try
        set output to ""
        set msgList to messages 1 thru $limit of inbox
        repeat with msg in msgList
            set msgId to id of msg
            set msgSubject to subject of msg
            set msgSender to extract name from sender of msg
            set output to output & "ID: " & msgId & " | From: " & msgSender & " | Subject: " & msgSubject & linefeed
        end repeat
        return output
    on error
        return "No messages found or Inbox is empty."
    end try
end tell
EOF
    """.trimIndent()

    private fun readMessageCommand(id: Int): String = """
osascript <<'EOF'
tell application "Mail"
    try
        set targetMsg to (first message of inbox whose id is $id)
        return content of targetMsg
    on error
        return "Error: Message with ID $id not found."
    end try
end tell
EOF
    """.trimIndent()

    private fun replyMessageCommand(id: Int, content: String): String = """
osascript <<'EOF'
tell application "Mail"
    activate
    try
        set targetMsg to (first message of inbox whose id is $id)
        set replyMsg to reply targetMsg with opening window
        
        -- Insert content at the beginning
        tell replyMsg
            set content to "$content" & return & return & content
        end tell
        return "Reply draft created successfully."
    on error
        return "Error: Could not find message $id or create reply."
    end try
end tell
EOF
    """.trimIndent()

    private fun sendNewMessageCommand(name: String, address: String, subject: String, content: String): String = """
osascript <<'EOF'
tell application "Mail"
    activate
    set newMessage to make new outgoing message with properties {subject:"$subject", content:"$content", visible:true}
    tell newMessage
        make new to recipient at end of to recipients with properties {name:"$name", address:"$address"}
    end tell
    return "New email draft created."
end tell
EOF
    """.trimIndent()
}

fun main() {
    val tool = ToolMail(ToolRunBashCommand)
    val result = tool.invoke(ToolMail.Input(ToolMail.MailAction.list_messages))
    println(result)
}