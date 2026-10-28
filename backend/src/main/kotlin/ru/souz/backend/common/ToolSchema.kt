package ru.souz.backend.common

import com.fasterxml.jackson.databind.JsonNode
import ru.souz.llms.LLMRequest

internal fun LLMRequest.Parameters.asProperty() = LLMRequest.Property(
    type = type, properties = properties, required = required, additionalProperties = additionalProperties,
)

internal fun normalizeToolSchema(schema: LLMRequest.Property, depth: Int, path: String): LLMRequest.Property {
    requireSchema(depth <= 16, "$path: Tool schema nesting exceeds 16 levels.")
    requireSchema(
        when (schema.type) {
            "object", "array", "string", "integer", "number", "boolean", "null" -> true
            else -> false
        },
        "$path: Unsupported tool schema type.",
    )
    schema.enum?.let { enum ->
        requireSchema(schema.type == "string", "$path: enum is only supported for string schemas.")
        requireSchema(enum.isNotEmpty(), "$path: enum must contain at least one value.")
    }
    if (schema.type == "array") {
        requireSchema(schema.items != null, "$path: Array schemas must define items.")
    } else {
        requireSchema(schema.items == null, "$path: items is only supported for array schemas.")
    }
    requireSchema(
        schema.type == "object" || schema.properties == null,
        "$path: properties is only supported for object schemas.",
    )
    requireSchema(
        schema.type == "object" || schema.required == null,
        "$path: required is only supported for object schemas.",
    )
    requireSchema(
        schema.type == "object" || schema.additionalProperties == null,
        "$path: additionalProperties is only supported for object schemas.",
    )
    requireSchema(
        schema.required.orEmpty().all { it in schema.properties.orEmpty() },
        "$path: Every required entry must exist in properties.",
    )
    return schema.copy(
        properties = schema.properties?.toSortedMap()?.mapValues { (name, child) ->
            normalizeToolSchema(child, depth + 1, "$path/properties/${name.replace("~", "~0").replace("/", "~1")}")
        },
        items = schema.items?.let { normalizeToolSchema(it, depth + 1, "$path/items") },
        required = schema.required?.distinct()?.sorted(),
        enum = schema.enum?.distinct()?.sorted(),
    )
}

internal fun toolArgumentsError(schema: LLMRequest.Parameters, arguments: JsonNode): String? =
    propertyError(schema.asProperty(), arguments)

private fun propertyError(schema: LLMRequest.Property, value: JsonNode): String? {
    val correctType = when (schema.type) {
        "object" -> value.isObject
        "array" -> value.isArray
        "string" -> value.isTextual
        "integer" -> value.isNumber && value.decimalValue().stripTrailingZeros().scale() <= 0
        "number" -> value.isNumber
        "boolean" -> value.isBoolean
        "null" -> value.isNull
        else -> false
    }
    if (!correctType) return "Expected ${schema.type}."
    if (schema.enum?.contains(value.asText()) == false) return "Value is outside the allowed enum."
    if (value.isObject) {
        if (schema.required.orEmpty().any { !value.has(it) }) return "A required argument is missing."
        val properties = schema.properties.orEmpty()
        if (schema.additionalProperties == false && value.fieldNames().asSequence().any { it !in properties }) {
            return "Unknown argument."
        }
        properties.forEach { (name, child) -> value[name]?.let { propertyError(child, it) }?.let { return it } }
    }
    if (value.isArray) value.forEach { item -> propertyError(requireNotNull(schema.items), item)?.let { return it } }
    return null
}


private fun requireSchema(valid: Boolean, message: String) { require(valid) { message } }
