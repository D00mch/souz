package ru.abledo.tool.mail

internal object MailAppleScriptCommands {
    fun unreadCountCommand(limit: Int): String = """
    osascript <<'EOF'
    tell application "Mail"
        set unreadCount to (count of (messages of inbox whose read status is false))
        
        if unreadCount > $limit then
            return $limit
        else
            return unreadCount
        end if
    end tell
EOF
""".trimIndent()

    fun listMessagesCommand(limit: Int): String = """
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

    fun readMessageCommand(id: Int): String = """
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

    fun replyMessageCommand(id: Int, content: String): String = """
        osascript <<'EOF'
        set targetId to $id

        tell application "Mail"
            activate

            try
                set targetMessage to (first message of inbox whose id is targetId)
                set newReply to reply targetMessage with opening window

            on error
                display dialog "Письмо с ID $id не найдено во Входящих." buttons {"OK"}
                return
            end try
        end tell

        delay 1

        tell application "System Events"
            tell process "Mail"
                keystroke "$content"

                keystroke return
                keystroke return
            end tell
        end tell
EOF
    """.trimIndent()

    fun sendNewMessageCommand(name: String, address: String, subject: String, content: String): String = """
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
