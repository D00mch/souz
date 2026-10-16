package ru.souz.backend.personality

import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import ru.souz.backend.channels.ChannelDeliveryService
import ru.souz.backend.events.service.AgentEventService
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.client.LiveClientToolDispatcher
import ru.souz.backend.settings.model.PersonalitySnapshot
import ru.souz.backend.settings.repository.UserSettingsRepository

internal class PersonalitySync(
    private val repository: UserSettingsRepository,
    private val dispatcher: LiveClientToolDispatcher,
    private val channels: ChannelDeliveryService,
    private val events: AgentEventService,
) {
    suspend fun connected(chat: Chat) {
        if (chat.clientType != "backend") return
        try {
            val outcome = deliver(chat.userId, repository.personality(chat.userId), chat.id, null)
            if (outcome !in setOf("synced", "superseded")) warn(chat.id, outcome)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { warn(chat.id, "unavailable") }
    }

    suspend fun deliver(userId: String, snapshot: PersonalitySnapshot, preferred: UUID?, threadId: UUID?): String {
        repeat(3) { attempt ->
            if (attempt > 0) delay(attempt * 1_000L)
            try {
                val chat = orionChannel(userId, preferred) ?: return "pending"
                val reply = dispatcher.call(chat, "prompt.personality.set", mapOf(
                    "prompt" to snapshot.prompt.orEmpty(), "revision" to snapshot.revision,
                ), Duration.ofSeconds(10), sourceExecution = threadId, sourceThreadId = threadId)
                if (reply.status != "succeeded") {
                    if (reply.error?.code in permanentErrors) return "failed"
                    return@repeat
                }
                val version = reply.result?.get("revision")
                    ?.takeIf { it.isIntegralNumber && it.canConvertToLong() }?.longValue() ?: return@repeat
                when (reply.result.path("outcome").asText()) {
                    "applied", "unchanged" -> if (version == snapshot.revision) return "synced"
                    "stale" -> if (version in (snapshot.revision + 1)..PersonalitySnapshot.MAX_REVISION) {
                        return if (repository.personality(userId).revision == version) "superseded" else "conflict"
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Unknown delivery: retry this snapshot, not the mutation. */ }
        }
        return "pending"
    }

    private suspend fun orionChannel(userId: String, preferred: UUID?): Chat? {
        val ids = (listOfNotNull(preferred) + events.liveChatIds(userId)).distinct()
        val owned = channels.resolveTargets(userId, ids)
        return ids.asSequence().mapNotNull(owned::get).firstOrNull {
            it.clientType == "backend" && events.hasLiveSubscriber(userId, it.id)
        }
    }

    private fun warn(chat: UUID, outcome: String) = LoggerFactory.getLogger(javaClass)
        .warn("Personality synchronization incomplete chatId={} outcome={}", chat, outcome)
}

private val permanentErrors = setOf("personality_context_missing", "personality_invalid_arguments",
    "personality_revision_conflict", "personality_not_configured")
