package ru.souz.backend.client

import com.fasterxml.jackson.databind.JsonNode
import java.util.UUID
import ru.souz.backend.http.invalidV1Request
import ru.souz.llms.LLMRequest
import ru.souz.llms.restJsonMapper

data class ClientToolDefinition(
    val name: String,
    val description: String,
    val parameters: LLMRequest.Parameters,
)

data class DiscoveredClientTools(val channelId: UUID, val tools: List<ClientToolDefinition>)

internal data class ClientToolDiscoveryResult(val tools: List<ClientToolDefinition>)

internal const val CLIENT_TOOL_DISCOVERY_NAME = "orion.tools.list"

internal fun List<ClientToolDefinition>.normalizeClientTools(
    serverNames: Set<String>
): List<ClientToolDefinition> {
    requireCatalogue(this.size <= 256, "tools must contain at most 256 definitions.")
    requireCatalogue(restJsonMapper.writeValueAsBytes(this).size <= 1_048_576, "tools must not exceed 1 MiB.")
    requireCatalogue(this.map { it.name }.distinct().size == this.size, "Tool names must be unique.")
    return this.withIndex().sortedBy { it.value.name }.map { (index, tool) ->
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
    "SpawnSubagent", "GetKnowledge", "SearchKnowledge", "SearchMemory", "ForgetMemoryFact", "ForgetAllMemory",
    "ConnectOAuthProvider", "SafeApiCall",
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
        when (schema.type) {
            "object", "array", "string", "integer", "number", "boolean", "null" -> true
            else -> false
        },
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
    "Client Skills are provided by channels of this user, independently of the source conversation. " +
        "The current conversation channel is the default for local commands when it provides the tool; " +
        "omit channelId to use it. The user's explicitly named other device takes priority: read the Skill's " +
        "providing channels and pass that channelId to RunSkillCommand.arguments. " +
        "A missing provider registration alone does not require rediscovery. The orion.tools.list Skill description " +
        "lists every channel with discoveryComplete: false means no successful catalogue for that channel, " +
        "true includes a successful empty catalogue. If the intended connected channel is undiscovered, " +
        "discover it before clarification or rejection; client_tool_discovery_required identifies that channel. " +
        "If its catalogue is already complete and the tool is absent, report that capability as unavailable " +
        "without another discovery. Refresh a completed catalogue only for a requested refresh or changed capabilities. " +
        "Do not ask which device when the request means this device. " +
        "Clarify only a genuinely ambiguous other destination; do not choose an arbitrary other device. " +
        "Only web.search may automatically use another connected provider when channelId is omitted. " +
        "channelId is reserved routing metadata, omitted from the registered schema and removed before dispatch. " +
        "Complete any needed discovery on the selected channel before delegating. " +
        "Discovery replaces that channel's discovered catalogue for this user. Conflicting definitions are unavailable. " +
        "When delegating, include the selected channelId in the task."
