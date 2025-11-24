package ru.abledo.db

enum class StorredType {
    FILES,
    BROWSER_HISTORY,
    NOTES,
    INSTALLED_APPS,
    INSTRUCTIONS,
}

data class StorredData(
    val text: String,
    val type: StorredType,
)
