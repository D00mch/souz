package ru.souz.backend.http

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import ru.souz.backend.events.model.AgentEvent
import ru.souz.backend.events.model.AgentEventType
import ru.souz.backend.events.model.ChoiceRequestedPayload
import ru.souz.backend.events.model.ChoiceOptionItemPayload
import ru.souz.backend.events.model.RawAgentEventPayload
import ru.souz.backend.events.model.ToolCallFinishedPayload
import ru.souz.backend.events.model.ToolCallStartedPayload
import ru.souz.llms.restJsonMapper

class BackendV1EventDtoPayloadTest {
    @Test
    fun `typed tool payload dto keeps native json previews and stable fields`() {
        val event = AgentEvent(
            id = UUID.fromString("11111111-1111-1111-1111-111111111111"),
            userId = "user-a",
            chatId = UUID.fromString("22222222-2222-2222-2222-222222222222"),
            executionId = UUID.fromString("33333333-3333-3333-3333-333333333333"),
            seq = 5L,
            type = AgentEventType.TOOL_CALL_STARTED,
            payload = ToolCallStartedPayload(
                toolCallId = "tool-1",
                name = "SearchDocs",
                argumentKeys = listOf("query", "token"),
                argumentsPreview = restJsonMapper.readTree(
                    """
                    {
                      "query": "hello",
                      "token": "[REDACTED]"
                    }
                    """.trimIndent()
                ),
            ),
            createdAt = Instant.parse("2026-05-02T10:00:00Z"),
        )

        val dto = event.toDto()

        assertEquals("tool-1", dto.payload["toolCallId"])
        assertEquals("SearchDocs", dto.payload["name"])
        assertEquals(listOf("query", "token"), dto.payload["argumentKeys"])
        assertEquals(
            "[REDACTED]",
            restJsonMapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(dto.payload["argumentsPreview"])["token"].asText(),
        )
    }

    @Test
    fun `typed finished tool payload dto exposes result preview as json and keeps status field`() {
        val event = AgentEvent(
            id = UUID.fromString("44444444-4444-4444-4444-444444444444"),
            userId = "user-a",
            chatId = UUID.fromString("55555555-5555-5555-5555-555555555555"),
            executionId = UUID.fromString("66666666-6666-6666-6666-666666666666"),
            seq = 6L,
            type = AgentEventType.TOOL_CALL_FINISHED,
            payload = ToolCallFinishedPayload(
                toolCallId = "tool-2",
                name = "SearchDocs",
                resultPreview = restJsonMapper.readTree("""{"items":["ok"]}"""),
                durationMs = 42L,
            ),
            createdAt = Instant.parse("2026-05-02T10:00:01Z"),
        )

        val dto = event.toDto()

        assertEquals("finished", dto.payload["status"])
        assertEquals(42L, dto.payload["durationMs"])
        assertEquals(
            "ok",
            restJsonMapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(dto.payload["resultPreview"])["items"][0].asText(),
        )
    }

    @Test
    fun `raw payload dto preserves backward compatible transport parsing`() {
        val event = AgentEvent(
            id = UUID.fromString("77777777-7777-7777-7777-777777777777"),
            userId = "user-a",
            chatId = UUID.fromString("88888888-8888-8888-8888-888888888888"),
            executionId = UUID.fromString("99999999-9999-9999-9999-999999999999"),
            seq = 7L,
            type = AgentEventType.OPTION_REQUESTED,
            payload = RawAgentEventPayload(
                values = mapOf(
                    "optionId" to "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                    "kind" to "choice",
                    "title" to "Pick one",
                    "selectionMode" to "single",
                    "options" to """[{"id":"a","label":"Alpha","content":"first"}]""",
                ),
            ),
            createdAt = Instant.parse("2026-05-02T10:00:02Z"),
        )

        val dto = event.toDto()
        val options = restJsonMapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(dto.payload["options"])

        assertEquals("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", dto.payload["optionId"])
        assertEquals("choice", dto.payload["kind"])
        assertEquals("single", dto.payload["selectionMode"])
        assertEquals("Alpha", options[0]["label"].asText())
    }

    @Test
    fun `typed choice payload dto exposes options without stringified json`() {
        val event = AgentEvent(
            id = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
            userId = "user-a",
            chatId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"),
            executionId = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd"),
            seq = 8L,
            type = AgentEventType.OPTION_REQUESTED,
            payload = ChoiceRequestedPayload(
                optionId = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"),
                kind = "choice",
                title = "Pick one",
                selectionMode = "single",
                options = listOf(
                    ChoiceOptionItemPayload(id = "a", label = "Alpha", content = "first"),
                ),
            ),
            createdAt = Instant.parse("2026-05-02T10:00:03Z"),
        )

        val dto = event.toDto()
        val options = restJsonMapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(dto.payload["options"])

        assertEquals("Pick one", dto.payload["title"])
        assertEquals("Alpha", options[0]["label"].asText())
        assertEquals("first", options[0]["content"].asText())
    }
}
