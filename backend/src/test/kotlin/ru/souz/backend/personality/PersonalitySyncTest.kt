package ru.souz.backend.personality

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import ru.souz.backend.channels.ChannelDeliveryService
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.client.ClientError
import ru.souz.backend.client.ClientToolOutcome
import ru.souz.backend.client.LiveClientToolDispatcher
import ru.souz.backend.events.service.AgentEventService
import ru.souz.backend.settings.model.PersonalitySnapshot
import ru.souz.llms.restJsonMapper

class PersonalitySyncTest {
    @Test
    fun `busy source yields to another channel or retries alone with the same snapshot`() = runTest {
        for (hasFallback in listOf(true, false)) {
            val source = Chat(UUID.randomUUID(), "owner", null, false, Instant.EPOCH, Instant.EPOCH)
            val fallback = source.copy(id = UUID.randomUUID())
            val available = (if (hasFallback) listOf(fallback, source) else listOf(source)).associateBy { it.id }
            val channels = mockk<ChannelDeliveryService> {
                coEvery { resolveTargets(source.userId, available.keys.toList()) } returns available
            }
            val events = mockk<AgentEventService> {
                every { liveChatIds(source.userId) } returns available.keys.toList()
                every { hasLiveSubscriber(source.userId, any()) } returns true
            }
            val attempts = mutableListOf<UUID>()
            val execution = UUID.randomUUID()
            val dispatcher = mockk<LiveClientToolDispatcher> {
                coEvery { call(any(), PERSONALITY_SYNC_COMMAND, mapOf("prompt" to "brief", "revision" to 2L),
                    Duration.ofSeconds(10), execution, execution) } answers {
                    attempts += firstArg<Chat>().id
                    if (attempts.size == 1) ClientToolOutcome("failed", null, ClientError("client_tool_busy", "Full queue"))
                    else ClientToolOutcome("succeeded", restJsonMapper.readTree("""{"outcome":"applied","revision":2}"""), null)
                }
            }
            val sync = PersonalitySync(mockk(), channels, events, dispatcher)
            assertEquals("synced", sync.deliver(source.userId, PersonalitySnapshot("brief", 2), source.id, execution))
            assertEquals(listOf(source.id, if (hasFallback) fallback.id else source.id), attempts)
        }
    }
}
