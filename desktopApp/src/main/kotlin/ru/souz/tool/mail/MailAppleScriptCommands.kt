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
    fun replyMessageCommand(): String = """
        osascript - "${'$'}@" <<'EOF'
        on run argv
        set targetId to (item 1 of argv) as integer
        set replyContent to item 2 of argv
        tell application "Mail"
            try
                set targetMessage to (first message of inbox whose id is targetId)
                set newReply to reply targetMessage opening window false
                tell newReply
                    make new paragraph at beginning of content with data (replyContent & return & return)
                end tell
                set visible of newReply to true
                activate
                return "Email reply draft created."
            on error errorMsg
                display dialog "Ошибка при создании ответа.\nДетали: " & errorMsg buttons {"OK"}
            end try
        end tell
        end run
EOF
    """.trimIndent()

    fun sendNewMessageCommand(): String = """
        osascript - "${'$'}@" <<'EOF'
        on run argv
        tell application "Mail"
            activate
            set newMessage to make new outgoing message with properties {subject:item 3 of argv, content:item 4 of argv, visible:true}
            tell newMessage
                make new to recipient at end of to recipients with properties {name:item 1 of argv, address:item 2 of argv}
            end tell
            return "New email draft created."
        end tell
        end run
EOF
    """.trimIndent()

    /** Rebuild the message at send time and compare all delivered fields to the approved snapshot. */
    fun approvalCommand(reply: Boolean, send: Boolean = false): String = """
        osascript - "${'$'}@" <<'EOF'
        use framework "Foundation"
        use scripting additions

        on messageSnapshot(theMessage)
            tell application "Mail"
                set fields to {sender of theMessage, subject of theMessage, (content of theMessage) as text, address of every to recipient of theMessage, address of every cc recipient of theMessage, address of every bcc recipient of theMessage}
            end tell
            return current application's NSDictionary's dictionaryWithObjects:fields forKeys:{"sender", "subject", "content", "to", "cc", "bcc"}
        end messageSnapshot

        on run argv
        tell application "Mail"
            try
            ${if (reply) """
            set targetId to (item 1 of argv) as integer
            set targetMessage to (first message of inbox whose id is targetId)
            set newMessage to reply targetMessage opening window false
            tell newMessage
                make new paragraph at beginning of content with data ((item 2 of argv) & return & return)
            end tell
            """.trimIndent() else """
            set newMessage to make new outgoing message with properties {subject:item 3 of argv, content:item 4 of argv, visible:false}
            tell newMessage
                make new to recipient at end of to recipients with properties {name:item 1 of argv, address:item 2 of argv}
            end tell
            """.trimIndent()}
                set actualSnapshot to my messageSnapshot(newMessage)
                ${if (send) """
                set expectedText to current application's NSString's stringWithString:(last item of argv)
                set expectedData to expectedText's dataUsingEncoding:(current application's NSUTF8StringEncoding)
                set expectedSnapshot to current application's NSJSONSerialization's JSONObjectWithData:expectedData options:0 |error|:(missing value)
                if not ((actualSnapshot's isEqualToDictionary:expectedSnapshot) as boolean) then
                    error "Email changed since approval. Request a new preview; nothing was sent."
                end if
                if (send newMessage) then return "Email sent."
                error "Mail could not send the email."
                """.trimIndent() else """
                set snapshotData to current application's NSJSONSerialization's dataWithJSONObject:actualSnapshot options:0 |error|:(missing value)
                set snapshotText to current application's NSString's alloc()'s initWithData:snapshotData encoding:(current application's NSUTF8StringEncoding)
                close newMessage saving no
                return snapshotText as text
                """.trimIndent()}
            on error errorMsg number errorNumber
                try
                    close newMessage saving no
                end try
                error errorMsg number errorNumber
            end try
        end tell
        end run
EOF
    """.trimIndent()
}
