package ru.gigadesk.tool.browser

import ru.gigadesk.tool.*
import java.io.File
import java.util.regex.Pattern

/**
 * Returns information from Google Chrome: browsing history, bookmarks, open tabs or current tab URL.
 * Works on macOS only and uses system tools such as `sqlite3`, `cat` and AppleScript.
 */
class ToolChromeInfo(private val bash: ToolRunBashCommand) : ToolSetup<ToolChromeInfo.Input> {
    enum class InfoType { history, bookmarks, tabs, currentTabUrl, pageText }
    data class Input(
        @InputParamDescription("Type of information to fetch")
        val type: InfoType,
        @InputParamDescription("URL of the page to get text from")
        val url: String? = null,
        @InputParamDescription("Number of lines to return if provided")
        val count: Int? = 50
    )
    override val name: String = "ChromeInfo"
    override val description: String = "Returns Google Chrome history, bookmarks, open tabs and URL of current tab"
    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Покажи историю Chrome",
            params = mapOf("type" to InfoType.history)
        ),
        FewShotExample(
            request = "Найди закладку про разработку в Chrome",
            params = mapOf("type" to InfoType.bookmarks)
        ),
        FewShotExample(
            request = "Какие вкладки открыты в Хроме?",
            params = mapOf("type" to InfoType.tabs)
        ),
        FewShotExample(
            request = "Дай ссылку с текущей вкладки Chrome",
            params = mapOf("type" to InfoType.currentTabUrl)
        ),
        FewShotExample(
            request = "Прочитай текст на текущей странице браузера",
            params = mapOf("type" to InfoType.pageText)
        )
    )
    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Information from Google Chrome")
        )
    )

    override fun invoke(input: Input): String {
        return when (input.type) {
            InfoType.history -> {
                // Chrome locks the DB file, so we copy it to /tmp first
                val copyCmd = "cp \"${System.getProperty("user.home")}/Library/Application Support/Google/Chrome/Default/History\" /tmp/ChromeHistory_copy"
                bash.sh(copyCmd)
                val result = bash.sh(historyCommand(input.count))
                bash.sh("rm /tmp/ChromeHistory_copy") // Cleanup
                result
            }
            InfoType.bookmarks -> parseChromeBookmarks(bash.sh(bookmarksCommand())).toString()
            InfoType.tabs -> {
                bash.sh(tabsCommand()).lines().joinToString { line ->
                    // Chrome output format in tabsCommand is slightly different, just passing through cleanly
                    line.trim()
                }
            }
            InfoType.currentTabUrl -> bash.sh(currentTabUrlCommand())
            InfoType.pageText -> {
                val targetUrl = input.url ?: bash.sh(currentTabUrlCommand()).trim()
                if (targetUrl.isBlank()) {
                    "Error: URL is required for pageText"
                } else {
                    bash.sh(pageTextCommand(targetUrl))
                }
            }
        }
    }

    // Chrome stores time in microseconds since 1601-01-01.
    // Conversion: (time / 1000000) - 11644473600 converts to unix epoch seconds.
    private fun historyCommand(count: Int? = 50): String = """
        sqlite3 /tmp/ChromeHistory_copy "
            SELECT
                datetime((last_visit_time/1000000)-11644473600, 'unixepoch', 'localtime') AS visit_date,
                url,
                title
            FROM urls
            ORDER BY visit_date DESC
            LIMIT $count;
        "
    """.trimIndent()

    private fun bookmarksCommand(): String = """
        cat "${System.getProperty("user.home")}/Library/Application Support/Google/Chrome/Profile 1/Bookmarks"
    """.trimIndent()

    private fun tabsCommand(): String = """
osascript <<'EOF'
tell application "Google Chrome"
    set output to ""
    set windowIndex to 1
    repeat with w in windows
        set tabIndex to 1
        repeat with t in tabs of w
            set tabName to title of t
            set output to output & "Win " & windowIndex & " Tab " & tabIndex & ": " & tabName & linefeed
            set tabIndex to tabIndex + 1
        end repeat
        set windowIndex to windowIndex + 1
    end repeat
    return output
end tell
EOF
    """.trimIndent()

    private fun currentTabUrlCommand(): String = """
        osascript <<'EOF'
            tell application "Google Chrome"
                if (count of windows) > 0 then
                    return URL of active tab of front window
                else
                    return ""
                end if
            end tell
        EOF
    """.trimIndent()

    private fun pageTextCommand(url: String): String = """
        if command -v lynx >/dev/null 2>&1; then
            lynx -dump '$url'
        else
            curl -L '$url'
        fi
    """.trimIndent()
}

/**
 * Parses Chrome Bookmarks JSON specifically looking for "name" and "url" pairs.
 * Since we want to avoid heavy external dependencies like Jackson/Gson for this tool,
 * we use a robust Regex approach to scan the JSON structure.
 */
private fun parseChromeBookmarks(jsonString: String): Map<String, String> {
    val bookmarks = mutableMapOf<String, String>()

    // Pattern looks for objects containing "name": "..." and "url": "..."
    // Chrome bookmarks JSON structure usually has keys in predictable order,
    // but we search broadly for blocks that have a url type.

    // 1. Simple extraction of all blocks that have type "url"
    // This regex matches: "name": "Value", ... "type": "url", ... "url": "Value"
    // It is lenient to handle whitespace and order variations to an extent.

    val entryPattern = Pattern.compile(
        "\"name\":\\s*\"(.*?)\".*?\"url\":\\s*\"(.*?)\"",
        Pattern.DOTALL
    )

    val matcher = entryPattern.matcher(jsonString)

    while (matcher.find()) {
        val name = matcher.group(1)
        val url = matcher.group(2)

        // Basic cleanup of escaped characters if necessary (Chrome escapes quotes as \")
        val cleanName = name.replace("\\\"", "\"")

        if (url.startsWith("http")) {
            bookmarks[cleanName] = url
        }
    }

    return bookmarks
}

fun main() {
    val tool = ToolChromeInfo(ToolRunBashCommand)
    val result = tool.invoke(ToolChromeInfo.Input(ToolChromeInfo.InfoType.history))
    println(result)
}