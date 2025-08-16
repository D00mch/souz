package com.dumch.tool.desktop

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
    enum class InfoType { history, bookmarks, tabs, currentTab }
    data class Input(
        @InputParamDescription("Type of information to fetch")
        val type: InfoType,
    )
    override val name: String = "SafariInfo"
    override val description: String = "Returns Safari history, bookmarks, open tabs or current tab URL"
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
            params = mapOf("type" to InfoType.currentTab)
        ),
        FewShotExample(
            request = "Сделай обзор открытой вкладки",
            params = mapOf("type" to InfoType.currentTab)
        )
    )
    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Information from Safari")
        )
    )

    override fun invoke(input: Input): String {
        return when (input.type) {
            InfoType.history -> bash.sh(historyCommand())
            InfoType.bookmarks -> parseSafariBookmarks(bash.sh(bookmarksCommand())).let {
                objectMapper.writeValueAsString(it)
            }
            InfoType.tabs -> bash.sh(tabsCommand())
            InfoType.currentTab -> bash.sh(currentTabCommand())
        }
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

    private fun currentTabCommand(): String = """
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
    val result = tool.invoke(ToolSafariInfo.Input(ToolSafariInfo.InfoType.history))
    println(result)
}
