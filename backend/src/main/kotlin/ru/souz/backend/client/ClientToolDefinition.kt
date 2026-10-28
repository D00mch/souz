package ru.souz.backend.client

import com.fasterxml.jackson.databind.JsonNode
import java.util.UUID
import ru.souz.backend.common.asProperty
import ru.souz.backend.common.normalizeToolSchema
import ru.souz.backend.common.toolArgumentsError
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
            tool.name !in serverNames && tool.name !in reservedSkillNames && "__" !in tool.name,
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
        val schema = try { normalizeToolSchema(tool.parameters.asProperty(), 0, path) }
        catch (e: IllegalArgumentException) { throw invalidV1Request(requireNotNull(e.message)) }
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

internal fun clientArgumentsError(schema: LLMRequest.Parameters, arguments: JsonNode): String? =
    toolArgumentsError(schema, arguments)

internal const val CLIENT_TOOL_ROUTING_INSTRUCTIONS =
    "Client Skills run on the current device by default. To target another connected device, or when there is no " +
        "current client, use ListActiveChannels and add the selected channelId to RunSkillCommand.arguments. " +
        "channelId is reserved routing metadata, omitted from the registered schema and removed before dispatch. " +
        "To discover additional Orion capabilities, invoke orion.tools.list on the selected channel. " +
        "Discovered Skills remain bound to that channel and are remembered in this conversation until the next discovery. " +
        "If the channel is unavailable, select a live channel and rediscover. Discover before delegating selected Skills. " +
        "When delegating, include the target channelId in the task or select ListActiveChannels for the child."
