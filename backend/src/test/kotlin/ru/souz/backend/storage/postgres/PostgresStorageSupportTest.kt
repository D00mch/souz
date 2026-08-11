package ru.souz.backend.storage.postgres

import com.fasterxml.jackson.module.kotlin.readValue
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import ru.souz.backend.agent.session.AgentConversationState

class PostgresStorageSupportTest {
    @Test
    fun `legacy active agent id is ignored when reading conversation context`() {
        val context = postgresStorageMapper.readValue<StoredConversationContext>(
            """
            {
              "schemaVersion": 1,
              "activeAgentId": "legacy-agent",
              "history": [],
              "temperature": 0.4,
              "locale": "en-US",
              "timeZone": "Europe/Amsterdam"
            }
            """.trimIndent()
        )

        assertEquals(1, context.schemaVersion)
        assertEquals(emptyList(), context.history)
        assertEquals(0.4f, context.temperature)
        assertEquals("en-US", context.locale)
        assertEquals("Europe/Amsterdam", context.timeZone)
    }

    @Test
    fun `conversation context serialization omits legacy active agent id`() {
        val state = AgentConversationState(
            userId = "user-a",
            chatId = UUID.randomUUID(),
            schemaVersion = 1,
            history = emptyList(),
            temperature = 0.4f,
            locale = Locale.forLanguageTag("en-US"),
            timeZone = ZoneId.of("Europe/Amsterdam"),
            basedOnMessageSeq = 0L,
            updatedAt = Instant.parse("2026-08-09T00:00:00Z"),
            rowVersion = 0L,
        )

        val json = postgresStorageMapper.readTree(state.toContextJson())

        assertFalse(json.has("activeAgentId"))
    }
}
