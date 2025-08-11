package com.dumch.tool.desktop

import com.dumch.tool.InputParamDescription
import com.dumch.tool.ToolRunBashCommand
import com.dumch.tool.ToolSetup

/**
 * Returns information from Safari: browsing history, bookmarks or open tabs.
 * Works on macOS only and uses system tools such as `sqlite3`, `plutil` and AppleScript.
 */
class ToolSafariInfo(private val bash: ToolRunBashCommand) : ToolSetup<ToolSafariInfo.Input> {

    override val name: String = "SafariInfo"
    override val description: String = "Returns Safari history, bookmarks or open tabs"

    class Input(
        @InputParamDescription("Type of information to fetch: 'history', 'bookmarks', or 'tabs'")
        val type: String,
    )

    override fun invoke(input: Input): String = when (input.type.lowercase()) {
        "history" -> bash.invoke(ToolRunBashCommand.Input(historyCommand()))
        "bookmarks" -> bash.invoke(ToolRunBashCommand.Input(bookmarksCommand()))
        "tabs" -> bash.invoke(ToolRunBashCommand.Input(tabsCommand()))
        else -> "Unknown type: ${input.type}"
    }

    private fun historyCommand(): String = """
        sqlite3 ~/Library/Safari/History.db "
            SELECT
                datetime(visit_time + 978307200,'unixepoch') AS last_visit,
                url
            FROM history_visits
            JOIN history_items ON history_items.id = history_visits.history_item
            ORDER BY last_visit DESC
            LIMIT 50;
        "
    """.trimIndent()

    private fun bookmarksCommand(): String = """
        plutil -convert xml1 -o - ~/Library/Safari/Bookmarks.plist
    """.trimIndent()

    private fun tabsCommand(): String = """
        osascript <<'EOF'
            tell application "Safari"
                set output to ""
                repeat with w in windows
                    repeat with t in tabs of w
                        set output to output & URL of t & linefeed
                    end repeat
                end repeat
                return output
            end tell
        EOF
    """.trimIndent()
}

