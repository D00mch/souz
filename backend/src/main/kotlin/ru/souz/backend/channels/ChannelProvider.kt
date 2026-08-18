package ru.souz.backend.channels

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

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

/** Aggregates registered [ChannelProvider]s. Providers list and address their own destinations. */
class ChannelProviderRegistry(providers: List<ChannelProvider>) {
    private val providersByType: Map<String, ChannelProvider> =
        providers.associateBy { it.channelType }.also { indexed ->
            require(indexed.size == providers.size) {
                "Duplicate channel provider type."
            }
        }

    suspend fun listAll(userId: String): List<ChannelDescriptor> = coroutineScope {
        // Each provider does its own independent I/O — run them concurrently rather than awaiting
        // one provider's listChannels before starting the next.
        providersByType.values.map { provider -> async { provider.listChannels(userId) } }.awaitAll().flatten()
    }

    suspend fun send(
        userId: String,
        channelType: String,
        channelId: String,
        text: String,
    ): ChannelSendResult =
        providersByType[channelType]?.sendMessage(userId, channelId, text)
            ?: ChannelSendResult.Failed("Unknown or unsupported channel type: '$channelType'.")
}
