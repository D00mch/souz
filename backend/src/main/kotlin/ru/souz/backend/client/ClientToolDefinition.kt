package ru.souz.backend.client

import com.fasterxml.jackson.databind.JsonNode
import ru.souz.backend.http.invalidV1Request
import ru.souz.llms.LLMRequest
import ru.souz.llms.restJsonMapper

data class ClientToolDefinition(
    val name: String,
    val description: String,
    val parameters: LLMRequest.Parameters,
)

internal fun normalizeClientTools(tools: List<ClientToolDefinition>, serverNames: Set<String>): List<ClientToolDefinition> {
    fun check(valid: Boolean, message: String) { if (!valid) throw invalidV1Request(message) }
    check(tools.size <= 256, "tools must contain at most 256 definitions.")
    check(restJsonMapper.writeValueAsBytes(tools).size <= 1_048_576, "tools must not exceed 1 MiB.")
    check(tools.map { it.name }.distinct().size == tools.size, "Tool names must be unique.")
    return tools.sortedBy { it.name }.map { tool ->
        check(Regex("[A-Za-z][A-Za-z0-9_.-]{0,63}").matches(tool.name), "Invalid tool name.")
        check(tool.name !in serverNames && tool.name !in coreToolNames, "Tool name is reserved by Souz.")
        check(tool.description.isNotBlank() && tool.description.length <= 16_384, "Invalid tool description.")
        check(tool.parameters.type == "object", "Tool parameters must have an object root.")
        check("channelId" !in tool.parameters.properties, "channelId is reserved for channel routing.")
        val schema = normalizeSchema(tool.parameters.asProperty(), 0)
        tool.copy(parameters = tool.parameters.copy(properties = schema.properties.orEmpty(), required = schema.required.orEmpty()))
    }
}

private val coreToolNames = setOf(
    "GetSkillByName", "GetSkillsByCategory", "GetSkillsNamesByCategory", "RunSkillCommand",
    "SpawnSubagent", "GetKnowledge", "SearchKnowledge", "SearchMemory",
)

private fun LLMRequest.Parameters.asProperty() = LLMRequest.Property(
    type = type, properties = properties, required = required, additionalProperties = additionalProperties,
)

private fun normalizeSchema(schema: LLMRequest.Property, depth: Int): LLMRequest.Property {
    if (depth > 16 || schema.type !in setOf("object", "array", "string", "integer", "number", "boolean", "null") ||
        (schema.enum != null && schema.type != "string") ||
        (schema.type == "array") != (schema.items != null) ||
        (schema.type != "object" && (schema.properties != null || schema.required != null || schema.additionalProperties != null)) ||
        !schema.required.orEmpty().all { it in schema.properties.orEmpty() }
    ) throw invalidV1Request("Unsupported tool schema; maximum nesting depth is 16.")
    return schema.copy(
        properties = schema.properties?.toSortedMap()?.mapValues { normalizeSchema(it.value, depth + 1) },
        items = schema.items?.let { normalizeSchema(it, depth + 1) },
        required = schema.required?.distinct()?.sorted(),
        enum = schema.enum?.distinct()?.sorted(),
    )
}

internal fun clientArgumentsError(schema: LLMRequest.Parameters, arguments: JsonNode): String? =
    propertyError(schema.asProperty(), arguments)

private fun propertyError(schema: LLMRequest.Property, value: JsonNode): String? {
    val correctType = when (schema.type) {
        "object" -> value.isObject
        "array" -> value.isArray
        "string" -> value.isTextual
        "integer" -> value.isIntegralNumber
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
        properties.forEach { (name, child) ->
            value[name]?.let { propertyError(child, it) }?.let { return it }
        }
    }
    if (value.isArray) value.forEach { item -> propertyError(requireNotNull(schema.items), item)?.let { return it } }
    return null
}
