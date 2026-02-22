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
                set output to output & "ID: " & msgId & " | Date: " & (msgDate as string) & " | From: " & msgSender & " | Subject: " & msgSubject & linefeed
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

    fun replyMessageCommand(id: Int, content: String): String {
        // Подготавливаем текст:
        // 1. Экранируем слеши и кавычки, чтобы не сломать синтаксис AppleScript.
        // 2. Заменяем переносы строк на склейку со специальным символом return в AppleScript.
        val safeContent = content
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\" & return & \"")

        return """
        osascript <<'EOF'
        set targetId to $id
        
        tell application "Mail"
            try
                -- Находим оригинальное письмо
                set targetMessage to (first message of inbox whose id is targetId)
                
                -- Создаем ответ, но пока не показываем его
                set newReply to reply targetMessage opening window false
                
                tell newReply
                    -- Аккуратно добавляем наш текст новым абзацем в начало письма
                    make new paragraph at beginning of content with data ("$safeContent" & return & return)
                    
                    -- Показываем готовое письмо пользователю для проверки
                    set visible to true
                end tell
                
                -- Переводим фокус на Mail
                activate
                
            on error errorMsg
                display dialog "Ошибка при создании ответа.\nДетали: " & errorMsg buttons {"OK"}
            end try
        end tell
        EOF
    """.trimIndent()
    }

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
