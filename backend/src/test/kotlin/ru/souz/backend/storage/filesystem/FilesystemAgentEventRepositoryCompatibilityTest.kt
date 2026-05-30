package ru.souz.backend.storage.filesystem

import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import ru.souz.backend.events.model.AgentEventType
import ru.souz.backend.events.model.RawAgentEventPayload

class FilesystemAgentEventRepositoryCompatibilityTest {
    @Test
    fun `repository reads legacy map payloads as raw payloads`() = runTest {
        val dataDir = Files.createTempDirectory("filesystem-event-compat")
        val userId = "opaque/user@example.com"
        val chatId = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val executionId = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val payload = mapOf(
            "optionId" to "33333333-3333-3333-3333-333333333333",
            "kind" to "choice",
            "title" to "Pick one",
            "selectionMode" to "single",
            "options" to """[{"id":"a","label":"Alpha","content":"first"}]""",
        )
        val mapper = filesystemStorageObjectMapper()
        val layout = FilesystemStorageLayout(dataDir)

        mapper.appendJsonValue(
            target = layout.eventsFile(userId, chatId),
            value = mapOf(
                "id" to "44444444-4444-4444-4444-444444444444",
                "userId" to userId,
                "chatId" to chatId.toString(),
                "executionId" to executionId.toString(),
                "seq" to 1L,
                "type" to AgentEventType.OPTION_REQUESTED.value,
                "payload" to payload,
                "createdAt" to Instant.parse("2026-05-02T10:00:00Z").toString(),
            ),
        )

        val repository = FilesystemAgentEventRepository(dataDir)
        val storedEvent = repository.listByChat(userId, chatId).single()

        val rawPayload = assertIs<RawAgentEventPayload>(storedEvent.payload)
        assertEquals(payload, rawPayload.values)
    }
}
