package ru.souz.backend.channels

/** A user-facing communication channel a message can be forwarded to (Telegram, a public-client chat, ...). */
data class ChannelDescriptor(
    val channelType: String,
    val channelId: String,
    val label: String,
)

sealed interface ChannelSendResult {
    data class Delivered(val detail: String) : ChannelSendResult
    data class Failed(val reason: String) : ChannelSendResult
}

/** One implementation per channel type — see `backend/src/main/kotlin/ru/souz/backend/channels/` siblings. */
interface ChannelProvider {
    val channelType: String

    suspend fun listChannels(userId: String): List<ChannelDescriptor>

    suspend fun sendMessage(userId: String, channelId: String, text: String): ChannelSendResult
}

/**
 * Aggregates registered [ChannelProvider]s. Providers list destinations; the registry may exclude
 * the current source channel when a structured source identity is available.
 */
class ChannelProviderRegistry(providers: List<ChannelProvider>) {
    private val providersByType: Map<String, ChannelProvider> =
        providers.associateBy { it.channelType }.also { indexed ->
            require(indexed.size == providers.size) {
                "Duplicate channel provider type."
            }
        }

    suspend fun listAll(userId: String, excludeChannelId: String? = null): List<ChannelDescriptor> =
        providersByType.values.flatMap { it.listChannels(userId) }.filterNot { it.channelId == excludeChannelId }

    suspend fun send(
        userId: String,
        channelType: String,
        channelId: String,
        text: String,
        excludeChannelId: String? = null,
    ): ChannelSendResult {
        if (excludeChannelId != null && channelId == excludeChannelId) {
            return ChannelSendResult.Failed("Cannot forward a message to the current channel.")
        }
        return providersByType[channelType]?.sendMessage(userId, channelId, text)
            ?: ChannelSendResult.Failed("Unknown or unsupported channel type: '$channelType'.")
    }
}
