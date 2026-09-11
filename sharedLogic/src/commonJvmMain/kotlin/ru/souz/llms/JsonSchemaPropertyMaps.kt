package ru.souz.llms

internal fun LLMRequest.Property.toJsonSchemaMap(
    unconstrainedArrayItems: Boolean = false,
): Map<String, Any> {
    val includeItems = unconstrainedArrayItems && type == "array"
    val capacity = 1 +
        (if (description != null) 1 else 0) +
        (if (enum != null) 1 else 0) +
        (if (includeItems) 1 else 0)
    return buildMap(capacity) {
        put("type", type)
        description?.let { put("description", it) }
        enum?.let { put("enum", it) }
        if (includeItems) {
            put("items", emptyMap<String, Any>())
        }
    }
}
