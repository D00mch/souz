package ru.souz.tool.mail

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
        set requestLimit to $limit
        set nowDate to (current date)
        set msgList to {}

        try
            set msgList to messages 1 thru requestLimit of inbox
        on error
            set msgList to messages of inbox
        end try

        if (count of msgList) is 0 then
            return "Inbox is empty."
        end if
        
        if class of msgList is not list then
            set msgList to {msgList}
        end if
        
        repeat with msg in msgList
            try
                set msgId to id of msg
                set msgSubject to subject of msg
                set msgSender to extract name from sender of msg
                set msgDate to date received of msg
                set ageDays to ((nowDate - msgDate) / days)
                if ageDays < 0 then set ageDays to 0
                set ageDaysRounded to ageDays div 1
                set output to output & "ID: " & msgId & " | Date: " & (msgDate as string) & " | AgeDays: " & ageDaysRounded & " | From: " & msgSender & " | Subject: " & msgSubject & linefeed
            on error
            end try
        end repeat
        
        return output
    on error errMsg
        return "Error reading mail: " & errMsg
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

    // Message fields travel as process arguments, never as shell or AppleScript source.
    fun replyMessageCommand(send: Boolean = false): String = """
        osascript - "${'$'}@" <<'EOF'
        on run argv
        set targetId to (item 1 of argv) as integer
        set replyContent to item 2 of argv

        tell application "Mail"
            try
                -- Находим оригинальное письмо
                set targetMessage to (first message of inbox whose id is targetId)
                
                -- Создаем ответ, но пока не показываем его
                set newReply to reply targetMessage opening window false
                
                tell newReply
                    -- Аккуратно добавляем наш текст новым абзацем в начало письма
                    make new paragraph at beginning of content with data (replyContent & return & return)
                end tell

                ${if (send) """
                if (send newReply) then
                    return "Email reply sent."
                end if
                error "Mail could not send the reply."
                """.trimIndent() else """
                set visible of newReply to true
                activate
                return "Email reply draft created."
                """.trimIndent()}
                
            on error errorMsg
                ${if (send) "error errorMsg" else """display dialog "Ошибка при создании ответа.\nДетали: " & errorMsg buttons {"OK"}"""}
            end try
        end tell
        end run
EOF
    """.trimIndent()

    fun sendNewMessageCommand(send: Boolean = false): String = """
        osascript - "${'$'}@" <<'EOF'
        on run argv
        set recipientName to item 1 of argv
        set recipientAddress to item 2 of argv
        set messageSubject to item 3 of argv
        set messageContent to item 4 of argv
        tell application "Mail"
            ${if (send) "" else "activate"}
            set newMessage to make new outgoing message with properties {subject:messageSubject, content:messageContent, visible:${!send}}
            tell newMessage
                make new to recipient at end of to recipients with properties {name:recipientName, address:recipientAddress}
            end tell
            ${if (send) """
            if (send newMessage) then
                return "Email sent."
            end if
            error "Mail could not send the email."
            """.trimIndent() else """return "New email draft created.""""}
        end tell
        end run
EOF
    """.trimIndent()
}
