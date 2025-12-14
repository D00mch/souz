package ru.abledo.tool.mail

import ru.abledo.tool.FewShotExample
import ru.abledo.tool.InputParamDescription
import ru.abledo.tool.ReturnParameters
import ru.abledo.tool.ReturnProperty
import ru.abledo.tool.ToolRunBashCommand
import ru.abledo.tool.ToolSetup

class ToolMailSearch(private val bash: ToolRunBashCommand) : ToolSetup<ToolMailSearch.Input> {

    data class Input(
        @InputParamDescription("The search query (keyword, name, topic).")
        val query: String,

        @InputParamDescription("Max results to return. Default: 5")
        val limit: Int = 5
    )

    override val name: String = "MailSearch"
    override val description: String = "Searches emails directly via Mail app (Subject & Sender). Slower than Spotlight but reliable."

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
            
            set output to output & "ID: " & msgId & " | From: " & msgSender & " | Subject: " & msgSubject & linefeed
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