package com.dumch.tool.browser

import com.dumch.giga.objectMapper
import com.dumch.tool.*
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource


/**
 * Returns information from Safari: browsing history, bookmarks, open tabs or current tab URL.
 * Works on macOS only and uses system tools such as `sqlite3`, `plutil` and AppleScript.
 */
class ToolSafariInfo(private val bash: ToolRunBashCommand) : ToolSetup<ToolSafariInfo.Input> {
    enum class InfoType { history, bookmarks, tabs, currentTabUrl, pageText }
    data class Input(
        @InputParamDescription("Type of information to fetch")
        val type: InfoType,
        @InputParamDescription("URL of the page to get text from")
        val url: String? = null,
        @InputParamDescription("Number of lines to return if provided")
        val count: Int? = 50
    )
    override val name: String = "SafariInfo"
    override val description: String = "Returns Safari history, bookmarks, open tabs and URL of current tab"
    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Покажи историю браузера",
            params = mapOf("type" to InfoType.history)
        ),
        FewShotExample(
            request = "Найди закладку про фильмы в Safari",
            params = mapOf("type" to InfoType.bookmarks)
        ),
        FewShotExample(
            request = "Покажи открытые вкладки в Safari",
            params = mapOf("type" to InfoType.tabs)
        ),
        FewShotExample(
            request = "Покажи адрес текущей вкладки Safari",
            params = mapOf("type" to InfoType.currentTabUrl)
        ),
        FewShotExample(
            request = "Расскажи кратко о чем рассказано на текущей странице",
            params = mapOf("type" to InfoType.pageText, "url" to "https://example.com")
        )
    )
    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Information from Safari")
        )
    )

    override fun invoke(input: Input): String {
        return when (input.type) {
            InfoType.history -> bash.sh(historyCommand(input.count))
            InfoType.bookmarks -> parseSafariBookmarks(bash.sh(bookmarksCommand())).let {
                objectMapper.writeValueAsString(it)
            }
            InfoType.tabs -> {
                bash.sh(tabsCommand()).lines().joinToString { line ->
                    val (index, tabName) = line.replace("\"", "").split(", ")
                    (tabName to index.toInt()).toString()
                    "$tabName: $index"
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

    private fun historyCommand(count: Int? = 50): String = """
        sqlite3 ~/Library/Safari/History.db "
            SELECT
                datetime(visit_time + 978307200,'unixepoch') AS last_visit,
                url,
                title
            FROM history_visits
            JOIN history_items ON history_items.id = history_visits.history_item
            ORDER BY last_visit DESC
            LIMIT $count;
        "
    """.trimIndent()

    private fun bookmarksCommand(): String = """
        plutil -convert xml1 -o - ~/Library/Safari/Bookmarks.plist
    """.trimIndent()

    private fun tabsCommand(): String = """
osascript <<'EOF'
tell application "Safari"
    set output to ""
    set tabIndex to 1
    repeat with w in windows
        repeat with t in tabs of w
            set tabName to name of t
            set output to output & tabIndex & ", " & quote & tabName & quote& linefeed
            set tabIndex to tabIndex + 1
        end repeat
    end repeat
    return text 1 thru -2 of output
end tell
EOF
    """.trimIndent()

    private fun currentTabUrlCommand(): String = """
        osascript <<'EOF'
            tell application "Safari"
                if exists (front document) then
                    return URL of front document
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

private fun parseSafariBookmarks(xmlString: String): Map<String, String> {
    val bookmarks = mutableMapOf<String, String>()
    try {
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val inputSource = InputSource(StringReader(xmlString))
        val document = builder.parse(inputSource)
        document.documentElement.normalize()

        // Start processing from the top-level <dict> element
        val dictElements = document.getElementsByTagName("dict")
        if (dictElements.length > 0) {
            processDictElement(dictElements.item(0) as Element, bookmarks)
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return bookmarks
}

private fun processDictElement(dict: Element, bookmarks: MutableMap<String, String>) {
    var currentNode: Node? = dict.firstChild
    var type: String? = null
    var title: String? = null
    var url: String? = null
    var childrenArray: Element? = null

    while (currentNode != null) {
        // Skip text nodes (whitespace)
        if (currentNode.nodeType == Node.ELEMENT_NODE && currentNode.nodeName == "key") {
            val key = currentNode.textContent
            // Get next element sibling, skipping text nodes
            var nextSibling = currentNode.nextSibling
            while (nextSibling != null && nextSibling.nodeType != Node.ELEMENT_NODE) {
                nextSibling = nextSibling.nextSibling
            }

            when (key) {
                "WebBookmarkType" -> {
                    if (nextSibling != null && nextSibling.nodeName == "string") {
                        type = nextSibling.textContent
                    }
                }
                "URIDictionary" -> {
                    if (nextSibling != null) {
                        title = extractTitleFromUriDictionary(nextSibling as Element)
                    }
                }
                "URLString" -> {
                    if (nextSibling != null && nextSibling.nodeName == "string") {
                        url = nextSibling.textContent
                    }
                }
                "Children" -> {
                    if (nextSibling != null && nextSibling.nodeName == "array") {
                        childrenArray = nextSibling as Element
                    }
                }
            }
        }
        currentNode = currentNode.nextSibling
    }

    // Process leaf node if it's a bookmark
    if (type == "WebBookmarkTypeLeaf" && title != null && url != null) {
        bookmarks[title] = url
    }

    // Process children if they exist
    childrenArray?.let { processArrayElement(it, bookmarks) }
}

private fun extractTitleFromUriDictionary(uriDict: Element): String? {
    var currentNode: Node? = uriDict.firstChild
    while (currentNode != null) {
        // Skip text nodes (whitespace)
        if (currentNode.nodeType == Node.ELEMENT_NODE && currentNode.nodeName == "key" &&
            currentNode.textContent == "title") {
            // Get next element sibling, skipping text nodes
            var titleElement = currentNode.nextSibling
            while (titleElement != null && titleElement.nodeType != Node.ELEMENT_NODE) {
                titleElement = titleElement.nextSibling
            }
            if (titleElement != null && titleElement.nodeName == "string") {
                return titleElement.textContent
            }
        }
        currentNode = currentNode.nextSibling
    }
    return null
}

private fun processArrayElement(array: Element, bookmarks: MutableMap<String, String>) {
    var currentNode: Node? = array.firstChild
    while (currentNode != null) {
        // Only process element nodes (skip text nodes)
        if (currentNode.nodeType == Node.ELEMENT_NODE && currentNode.nodeName == "dict") {
            processDictElement(currentNode as Element, bookmarks)
        }
        currentNode = currentNode.nextSibling
    }
}

fun main() {
    val tool = ToolSafariInfo(ToolRunBashCommand)
    val result = tool.invoke(ToolSafariInfo.Input(ToolSafariInfo.InfoType.tabs))
    println(result)
}
