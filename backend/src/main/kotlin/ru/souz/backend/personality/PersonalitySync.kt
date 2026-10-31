package ru.souz.backend.personality

import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.chat.repository.ChatRepository
import ru.souz.backend.client.ClientError
import ru.souz.backend.client.ClientToolChannel
import ru.souz.backend.client.LiveClientToolDispatcher
import ru.souz.backend.client.clientToolDiscoveryRequired
import ru.souz.backend.events.service.AgentEventService
import ru.souz.backend.settings.model.PersonalitySnapshot
import ru.souz.backend.settings.repository.UserSettingsRepository

internal const val PERSONALITY_SYNC_COMMAND = "prompt.personality.set"

internal class PersonalitySync(
    private val settings: UserSettingsRepository,
    private val chats: ChatRepository,
    private val events: AgentEventService,
    private val dispatcher: LiveClientToolDispatcher,
) {
    /** Discovery must precede mutation: retrying this result cannot save the style twice. */
    suspend fun discoveryRequired(userId: String, preferred: UUID?): ClientError? {
        val connected = connectedChannels(userId, preferred)
        if (connected.any { it.supportsPersonality() }) return null
        val unknown = connected.firstOrNull { !it.discoveryComplete } ?: return null
        return clientToolDiscoveryRequired(unknown.chat.id,
            "then retry personality.update with the same arguments. Nothing has been saved. " +
                "prompt.personality.set is hidden from discovery output; retry even when it is absent from the returned tools.")
    }

    suspend fun connected(chat: Chat) {
        try {
            if (connectedChannels(chat.userId, chat.id).none { it.chat.id == chat.id && it.supportsPersonality() }) return
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
                val eligible = connectedChannels(userId, preferred).filter { it.supportsPersonality() }
                // Retry another receiver after rejected publication; a lone busy receiver may recover.
                val chat = (eligible.firstOrNull { it.chat.id !in busyChannels } ?: eligible.firstOrNull())?.chat
                    ?: return "skipped"
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

    private suspend fun connectedChannels(userId: String, preferred: UUID?): List<ClientToolChannel> =
        chats.clientToolChannels(userId)
            .filter { events.hasLiveSubscriber(userId, it.chat.id) }
            .sortedWith(compareBy({ it.chat.id != preferred }, { it.chat.id.toString() }))

    private fun ClientToolChannel.supportsPersonality() = tools.any { it.name == PERSONALITY_SYNC_COMMAND }

    private val permanentErrors = setOf("personality_context_missing", "personality_invalid_arguments",
        "personality_revision_conflict", "personality_not_configured")
}
