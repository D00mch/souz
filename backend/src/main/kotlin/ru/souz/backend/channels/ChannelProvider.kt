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
    fun supports(channelType: String): Boolean

    suspend fun listChannels(userId: String): List<ChannelDescriptor>

    suspend fun sendMessage(userId: String, channelId: String, text: String): ChannelSendResult
}

/**
 * Aggregates all registered [ChannelProvider]s; adding a new channel type means binding one more
 * provider here. [excludeChannelId] — normally the calling tool's own conversation id — keeps the
 * "never forward a message back into its own conversation" rule in exactly one place instead of
 * each caller re-deriving and re-checking it, so a future caller can't silently reintroduce the bug
 * by forgetting the check.
 */
class ChannelProviderRegistry(private val providers: List<ChannelProvider>) {
    suspend fun listAll(userId: String, excludeChannelId: String? = null): List<ChannelDescriptor> =
        providers.flatMap { it.listChannels(userId) }.filterNot { it.channelId == excludeChannelId }

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
        return providers.firstOrNull { it.supports(channelType) }?.sendMessage(userId, channelId, text)
            ?: ChannelSendResult.Failed("Unknown or unsupported channel type: '$channelType'.")
    }
}
