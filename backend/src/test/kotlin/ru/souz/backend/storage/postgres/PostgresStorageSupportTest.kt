package ru.souz.backend.storage.postgres

import com.fasterxml.jackson.module.kotlin.readValue
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import ru.souz.backend.agent.session.AgentConversationState
import ru.souz.backend.agent.session.toConversationSession
import ru.souz.llms.FileContent
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest

class PostgresStorageSupportTest {
    @Test
    fun `legacy active agent id is ignored when reading conversation context`() {
        val context = postgresStorageMapper.readValue<StoredConversationContext>(
            """
            {
              "schemaVersion": 1,
              "activeAgentId": "legacy-agent",
              "history": [{"role":"user","content":"hello"}],
              "temperature": 0.4,
              "locale": "en-US",
              "timeZone": "Europe/Amsterdam"
            }
            """.trimIndent()
        )

        assertEquals(1, context.schemaVersion)
        assertEquals(listOf(LLMRequest.Message(LLMMessageRole.user, "hello")), context.history)
        assertEquals(emptyList(), context.replyAttachments)
        assertNull(context.attachmentExecutionId)
        assertEquals(0.4f, context.temperature)
        assertEquals("en-US", context.locale)
        assertEquals("Europe/Amsterdam", context.timeZone)
    }

    @Test
    fun `session codec preserves files and omits absent attachment state`() {
        val state = AgentConversationState(
            userId = "user-a",
            chatId = UUID.randomUUID(),
            schemaVersion = 1,
            history = listOf(LLMRequest.Message(LLMMessageRole.user, "hello")),
            temperature = 0.4f,
            locale = Locale.forLanguageTag("en-US"),
            timeZone = ZoneId.of("Europe/Amsterdam"),
            basedOnMessageSeq = 0L,
            updatedAt = Instant.parse("2026-08-09T00:00:00Z"),
            rowVersion = 0L,
        )

        val json = postgresStorageMapper.readTree(state.toContextJson())

        assertFalse(json.has("activeAgentId"))
        // Text-only sessions keep the field set that releases without attachments can read.
        assertFalse(json.has("attachmentExecutionId"))
        assertFalse(json.has("replyAttachments"))
        for (field in listOf("files", "replyFiles", "detachedReplyFiles")) assertFalse(json["history"][0].has(field))
        assertEquals(emptyList(), state.toConversationSession().replyAttachments)
        val file = FileContent("file-id", "report.md", "text/markdown", 12)
        val history = listOf(LLMRequest.Message(LLMMessageRole.function, "saved", files = listOf(file),
            replyFiles = listOf(file), detachedReplyFiles = listOf("obsolete")))
        val withFiles = state.copy(schemaVersion = 2, history = history, attachmentExecutionId = "execution", replyAttachments = listOf(file))
        val restored = postgresStorageMapper.readValue<StoredConversationContext>(withFiles.toContextJson())
        assertEquals(history, restored.history)
        assertEquals("file-id", postgresStorageMapper.readTree(withFiles.toContextJson())["replyAttachments"][0]["fileId"].asText())
        assertEquals(listOf(file), restored.replyAttachments)
        assertEquals("execution", restored.attachmentExecutionId)
        assertEquals(listOf(file), withFiles.toConversationSession().replyAttachments)
        for (version in listOf(0, 3)) assertFailsWith<IllegalArgumentException> {
            state.copy(schemaVersion = version).toConversationSession()
        }
    }
}
