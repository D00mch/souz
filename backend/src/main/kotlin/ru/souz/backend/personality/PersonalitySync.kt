package ru.souz.backend.personality

import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import ru.souz.backend.channels.ChannelDeliveryService
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.client.LiveClientToolDispatcher
import ru.souz.backend.events.service.AgentEventService
import ru.souz.backend.settings.model.PersonalitySnapshot
import ru.souz.backend.settings.repository.UserSettingsRepository

internal const val PERSONALITY_SYNC_COMMAND = "prompt.personality.set"

internal class PersonalitySync(
    private val settings: UserSettingsRepository,
    private val channels: ChannelDeliveryService,
    private val events: AgentEventService,
    private val dispatcher: LiveClientToolDispatcher,
) {
    suspend fun connected(chat: Chat) {
        if (chat.clientType != "backend") return
        try {
            deliver(chat.userId, settings.personality(chat.userId), chat.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            LoggerFactory.getLogger(javaClass).warn("Could not synchronize personality for chat {}", chat.id, failure)
        }
    }

    suspend fun deliver(userId: String, snapshot: PersonalitySnapshot, preferred: UUID?, execution: UUID? = null): String {
        val busyChannels = mutableSetOf<UUID>()
        repeat(3) { attempt ->
            if (attempt > 0) delay(attempt * 1_000L)
            try {
                val chat = channel(userId, preferred, busyChannels) ?: return "pending"
                val reply = dispatcher.call(chat, PERSONALITY_SYNC_COMMAND,
                    mapOf("prompt" to snapshot.prompt.orEmpty(), "revision" to snapshot.revision),
                    Duration.ofSeconds(10), execution, threadId = execution ?: UUID.randomUUID())
                if (reply.error?.code == "client_tool_busy") busyChannels += chat.id
                if (reply.error?.code in permanentErrors) return "failed"
                if (reply.status != "succeeded") return@repeat
                val result = reply.result ?: return@repeat
                val revision = result.path("revision").takeIf { it.isIntegralNumber && it.canConvertToLong() }
                    ?.longValue() ?: return@repeat
                when (result.path("outcome").asText()) {
                    "applied", "unchanged" -> if (revision == snapshot.revision) return "synced"
                    "stale" -> if (revision > snapshot.revision) {
                        return if (revision == settings.personality(userId).revision) "superseded" else "conflict"
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Retry delivery of this committed snapshot, never the personality mutation.
            }
        }
        return "pending"
    }

    private suspend fun channel(userId: String, preferred: UUID?, busyChannels: Set<UUID>): Chat? {
        val available = channels.resolveTargets(userId, events.liveChatIds(userId)).values
            .filter(::available).sortedBy { it.id != preferred }
        // Try another channel after rejected publication; a lone busy channel can recover on retry.
        return available.firstOrNull { it.id !in busyChannels } ?: available.firstOrNull()
    }

    private fun available(chat: Chat) = chat.clientType == "backend" && events.hasLiveSubscriber(chat.userId, chat.id)

    private val permanentErrors = setOf("personality_context_missing", "personality_invalid_arguments",
        "personality_revision_conflict", "personality_not_configured")
}
