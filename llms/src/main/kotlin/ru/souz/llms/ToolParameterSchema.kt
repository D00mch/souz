package ru.souz.llms

fun LLMRequest.Parameters.toToolSchema(): Map<String, Any> = LLMRequest.Property(
    type = type,
    properties = properties,
    required = required,
    additionalProperties = additionalProperties,
).toToolSchema()

private fun LLMRequest.Property.toToolSchema(): Map<String, Any> = buildMap {
    put("type", type)
    description?.let { put("description", it) }
    enum?.let { put("enum", it) }
    properties?.let { put("properties", it.mapValues { (_, property) -> property.toToolSchema() }) }
    required?.let { put("required", it) }
    additionalProperties?.let { put("additionalProperties", it) }
    if (type == "array" || items != null) {
        // Older server tools can omit an array's item schema.
        put("items", items?.toToolSchema() ?: emptyMap<String, Any>())
    }
}
