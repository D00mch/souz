package ru.abledo.tool.browser

import ru.abledo.tool.ToolRunBashCommand

fun ToolRunBashCommand.detectDefaultBrowser(): BrowserType {
    val cmd = "plutil -convert xml1 -o - ~/Library/Preferences/com.apple.LaunchServices/com.apple.launchservices.secure.plist"
    val xmlOutput = sh(cmd)

    val bundleId = parsePlistForHttpHandler(xmlOutput) ?: "com.apple.Safari" // Если настройки нет, по умолчанию это Safari

    return when {
        bundleId.contains("chrome", ignoreCase = true) -> BrowserType.CHROME
        bundleId.contains("safari", ignoreCase = true) -> BrowserType.SAFARI
        else -> BrowserType.OTHER
    }
}

private fun parsePlistForHttpHandler(xml: String): String? {
    val dicts = xml.split("</dict>")

    for (dict in dicts) {
        if (dict.contains("<key>LSHandlerURLScheme</key>") &&
            dict.contains("<string>http</string>")) {

            val pattern = "<key>LSHandlerRoleAll</key>\\s*<string>(.*?)</string>".toRegex(RegexOption.DOT_MATCHES_ALL)
            val match = pattern.find(dict)

            if (match != null) {
                return match.groupValues[1]
            }
        }
    }
    return null
}

enum class BrowserType {
    SAFARI, CHROME, OTHER, UNKNOWN
}

val BrowserType.prettyName
    get() = when (this) {
        BrowserType.CHROME -> "Google Chrome"
        BrowserType.SAFARI -> "Safari"
        else -> "Unknown" // Fallback для UNKNOWN или OTHER
    }

fun main() {
    println(ToolRunBashCommand.detectDefaultBrowser())
}
