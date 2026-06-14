package ru.souz.ui.graphlog

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

internal val graphJsonMapper = ObjectMapper()

private const val DATA_KEY_SELECTED_CATEGORIES = "selectedCategories"

data class ActiveToolsDiff(
    val before: List<String>,
    val after: List<String>,
    val added: List<String>,
    val removed: List<String>,
)

fun extractActiveToolsDiff(data: String): ActiveToolsDiff? {
    return try {
        val root = graphJsonMapper.readTree(data)
        val beforeTools = parseActiveTools(root.get("in")?.get("activeTools")).orEmpty()
        val afterTools = parseActiveTools(root.get("out")?.get("activeTools")).orEmpty()

        if (beforeTools.isEmpty() && afterTools.isEmpty()) {
            return null
        }

        val beforeSet = beforeTools.toSet()
        val afterSet = afterTools.toSet()
        val added = afterTools.filterNot { it in beforeSet }
        val removed = beforeTools.filterNot { it in afterSet }

        if (added.isEmpty() && removed.isEmpty()) {
            null
        } else {
            ActiveToolsDiff(before = beforeTools, after = afterTools, added = added, removed = removed)
        }
    } catch (_: Exception) {
        null
    }
}

fun isClassifyStep(nodeName: String): Boolean {
    val normalized = nodeName.lowercase()
    return normalized.contains("classify") || normalized.contains("классифик")
}

fun extractSelectedCategories(data: String): List<String> {
    return try {
        val root = graphJsonMapper.readTree(data)
        parseStringArray(root.get(DATA_KEY_SELECTED_CATEGORIES)).orEmpty()
    } catch (_: Exception) {
        emptyList()
    }
}

private fun parseActiveTools(node: JsonNode?): List<String>? {
    if (node == null || !node.isArray) return null
    return parseStringArray(node)
}

private fun parseStringArray(node: JsonNode?): List<String>? {
    if (node == null || !node.isArray) return null
    return node.mapNotNull { item ->
        item.asText().trim().takeIf { it.isNotEmpty() }
    }.distinct()
}
