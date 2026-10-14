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

internal fun normalizeClientTools(
    tools: List<ClientToolDefinition>,
    serverNames: Set<String>
): List<ClientToolDefinition> {
    requireCatalogue(tools.size <= 256, "tools must contain at most 256 definitions.")
    requireCatalogue(restJsonMapper.writeValueAsBytes(tools).size <= 1_048_576, "tools must not exceed 1 MiB.")
    requireCatalogue(tools.map { it.name }.distinct().size == tools.size, "Tool names must be unique.")
    return tools.withIndex().sortedBy { it.value.name }.map { (index, tool) ->
        requireCatalogue(Regex("[A-Za-z][A-Za-z0-9_.-]{0,63}").matches(tool.name), "Invalid tool name.")
        requireCatalogue(
            tool.name !in serverNames && tool.name !in reservedSkillNames,
            "Tool name is reserved by Souz."
        )
        requireCatalogue(
            tool.description.isNotBlank() && tool.description.length <= 16384,
            "Invalid tool description."
        )
        val path = "/tools/$index/parameters"
        requireCatalogue(tool.parameters.type == "object", "$path: Tool parameters must have an object root.")
        requireCatalogue(
            "channelId" !in tool.parameters.properties,
            "$path: channelId is reserved for channel routing."
        )
        val schema = normalizeSchema(tool.parameters.asProperty(), 0, path)
        val params = tool.parameters.copy(
            properties = schema.properties.orEmpty(),
            required = schema.required.orEmpty(),
        )
        tool.copy(parameters = params)
    }
}

// Include optional server capabilities even when their integrations are not configured.
private val reservedSkillNames = setOf(
    "GetSkillByName", "GetSkillsByCategory", "GetSkillsNamesByCategory", "RunSkillCommand",
    "SpawnSubagent", "GetKnowledge", "SearchKnowledge", "SearchMemory", "ConnectOAuthProvider", "SafeApiCall",
)

private fun requireCatalogue(valid: Boolean, message: String) {
    if (!valid) throw invalidV1Request(message)
}

private fun LLMRequest.Parameters.asProperty() = LLMRequest.Property(
    type = type, properties = properties, required = required, additionalProperties = additionalProperties,
)

private fun normalizeSchema(schema: LLMRequest.Property, depth: Int, path: String): LLMRequest.Property {
    requireCatalogue(depth <= 16, "$path: Tool schema nesting exceeds 16 levels.")
    requireCatalogue(
        schema.type in arrayOf("object", "array", "string", "integer", "number", "boolean", "null"),
        "$path: Unsupported tool schema type.",
    )
    schema.enum?.let { enum ->
        requireCatalogue(schema.type == "string", "$path: enum is only supported for string schemas.")
        requireCatalogue(enum.isNotEmpty(), "$path: enum must contain at least one value.")
    }
    if (schema.type == "array") {
        requireCatalogue(schema.items != null, "$path: Array schemas must define items.")
    } else {
        requireCatalogue(schema.items == null, "$path: items is only supported for array schemas.")
    }
    requireCatalogue(
        schema.type == "object" || schema.properties == null,
        "$path: properties is only supported for object schemas.",
    )
    requireCatalogue(
        schema.type == "object" || schema.required == null,
        "$path: required is only supported for object schemas.",
    )
    requireCatalogue(
        schema.type == "object" || schema.additionalProperties == null,
        "$path: additionalProperties is only supported for object schemas.",
    )
    requireCatalogue(
        schema.required.orEmpty().all { it in schema.properties.orEmpty() },
        "$path: Every required entry must exist in properties.",
    )
    return schema.copy(
        properties = schema.properties?.toSortedMap()?.mapValues { (name, child) ->
            normalizeSchema(child, depth + 1, "$path/properties/${name.replace("~", "~0").replace("/", "~1")}")
        },
        items = schema.items?.let { normalizeSchema(it, depth + 1, "$path/items") },
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

internal const val CLIENT_TOOL_ROUTING_INSTRUCTIONS =
    "Client Skills run on the current device by default. To target another connected device, or when there is no " +
        "current client, use ListActiveChannels and add the selected channelId to RunSkillCommand.arguments. " +
        "channelId is reserved routing metadata, omitted from the registered schema and removed before dispatch. " +
        "When delegating, include the target channelId in the task or select ListActiveChannels for the child."
