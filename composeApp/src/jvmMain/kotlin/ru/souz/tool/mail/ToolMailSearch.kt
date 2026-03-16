package ru.souz.tool.mail

import ru.souz.tool.FewShotExample
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolRunBashCommand
import ru.souz.tool.ToolSetup

class ToolMailSearch(private val bash: ToolRunBashCommand) : ToolSetup<ToolMailSearch.Input> {

    data class Input(
        @InputParamDescription("The search query (keyword, name, topic).")
        val query: String,

        @InputParamDescription("Max results to return. Default: 5")
        val limit: Int = 5
    )

    override val name: String = "MailSearch"
    override val description: String =
        "Searches emails directly via Mail app (Subject & Sender). Output includes Date and AgeDays. For urgent/important checks, treat messages as urgent only when they are relevant to the current date (typically AgeDays <= 3) and never by subject keywords alone."

    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Найди письмо от Артура про договор",
            params = mapOf("query" to "Артур договор")
        )
    )

    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "List of found emails with IDs")
        )
    )

    override fun invoke(input: Input): String {
        val safeQuery = input.query.replace("\"", "\\\"")
        val limit = input.limit

        val script = """
osascript <<'EOF'
tell application "Mail"
    try
        set searchPhrase to "$safeQuery"
        set output to ""
        set foundCount to 0
        set searchLimit to $limit
        set nowDate to (current date)
        
        -- Ищем письма, где тема ИЛИ отправитель содержат запрос
        -- Это соответствует тому, как вы тестировали вручную
        set foundMsgs to (every message of inbox whose subject contains searchPhrase or sender contains searchPhrase)
        
        if (count of foundMsgs) is 0 then
            return "No emails found matching '" & searchPhrase & "' in Inbox."
        end if
        
        -- Сортируем: берем самые свежие (обычно они в конце списка или начале, зависит от настроек, но AS возвращает список)
        -- Чтобы не усложнять, берем просто первые N из выборки. 
        -- Часто выборка идет по дате. Для надежности можно перебрать с конца, но начнем с простого.
        
        repeat with msg in foundMsgs
            if foundCount is greater than or equal to searchLimit then
                exit repeat
            end if
            
            set msgId to id of msg
            set msgSubject to subject of msg
            set msgSender to extract name from sender of msg
            set msgDate to date received of msg
            set ageDays to ((nowDate - msgDate) / days)
            if ageDays < 0 then set ageDays to 0
            set ageDaysRounded to ageDays div 1
            
            set output to output & "ID: " & msgId & " | Date: " & (msgDate as string) & " | AgeDays: " & ageDaysRounded & " | From: " & msgSender & " | Subject: " & msgSubject & linefeed
            set foundCount to foundCount + 1
        end repeat
        
        return output
    on error errMsg
        return "Error searching mail: " & errMsg
    end try
end tell
EOF
        """.trimIndent()

        return bash.sh(script)
    }
}

fun main() {
    val tool = ToolMailSearch(ToolRunBashCommand)
    println(tool.invoke(ToolMailSearch.Input("ндс")))
}
