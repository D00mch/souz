package ru.souz.backend.client

import ru.souz.backend.chat.model.Chat
import ru.souz.llms.LLMRequest

private val discoveryTool = ClientToolDefinition(
    CLIENT_TOOL_DISCOVERY_NAME,
    "Discover the tools supplied by this WebSocket channel and their input schemas.",
    LLMRequest.Parameters(type = "object", additionalProperties = false),
)

/** A channel supplies capabilities; it does not limit the capabilities of its conversation. */
data class ClientToolChannel(
    val chat: Chat,
    val tools: List<ClientToolDefinition>,
    /** A successful catalogue for this channel is known, including an empty one. */
    val discoveryComplete: Boolean,
)

internal data class UserClientTool(
    val definition: ClientToolDefinition,
    val channels: List<ClientToolChannel>,
)

internal fun List<ClientToolChannel>.userClientTools(excludedNames: Set<String>): List<UserClientTool> =
    flatMap { channel ->
        (channel.tools.filter { it.name != CLIENT_TOOL_DISCOVERY_NAME } + discoveryTool)
            .filter { it.name !in excludedNames }
            .map { it to channel }
    }.groupBy { it.first.name }.toSortedMap().values.mapNotNull { providers ->
        // TODO: support different definitions of one tool on different channels. Until then,
        // no provider (including an explicitly selected one) may bypass a conflicting name.
        if (providers.map { it.first }.distinct().size != 1) null
        else UserClientTool(providers.first().first, providers.map { it.second }.sortedBy { it.chat.id.toString() })
    }
