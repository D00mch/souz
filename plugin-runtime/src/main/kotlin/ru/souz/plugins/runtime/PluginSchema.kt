package ru.souz.plugins.runtime

import com.fasterxml.jackson.databind.JsonNode
import ru.souz.plugins.PluginToolDefinition

/** The subset represented losslessly by Souz's model-facing tool schema. */
internal fun validatePluginDefinition(definition: PluginToolDefinition) {
    require(definition.name.matches(Regex("[a-z][a-z0-9_]*"))) { "Invalid local tool name" }
    require(definition.description.isNotBlank()) { "Missing tool description" }
    validateSchema(definition.inputSchema, root = true, depth = 0)
}

private fun validateSchema(schema: JsonNode, root: Boolean, depth: Int) {
    require(depth <= 16 && schema.isObject) { "Invalid tool schema" }
    val allowed = if (root) setOf("type", "properties", "required")
        else setOf("type", "description", "enum", "items", "properties")
    require(schema.fieldNames().asSequence().all { it in allowed }) { "Unsupported tool schema keyword" }
    val type = schema.path("type").asText()
    require(type in schemaTypes) { "Unsupported schema type" }
    require(!root || type == "object") { "Tool arguments must be an object" }
    schema.get("description")?.let { require(it.isTextual) }
    schema.get("properties")?.let { properties ->
        require(type == "object" && properties.isObject)
        properties.forEach { validateSchema(it, false, depth + 1) }
    }
    schema.get("required")?.let { required ->
        require(required.isArray && required.all { it.isTextual && schema.path("properties").has(it.asText()) })
    }
    schema.get("enum")?.let { values -> require(type == "string" && values.isArray && values.all { it.isTextual }) }
    schema.get("items")?.let { require(type == "array"); validateSchema(it, false, depth + 1) }
    require(type != "array" || schema.has("items")) { "Array schema requires items" }
}

private val schemaTypes = setOf("object", "array", "string", "number", "integer", "boolean")
