package ru.gigadesk.db

enum class StorredType {
    FILES,
    BROWSER_HISTORY,
    NOTES,
    INSTALLED_APPS,
    INSTRUCTIONS,
    DEFAULT_BROWSER,
}

data class StorredData(
    val text: String,
    val type: StorredType,
)
