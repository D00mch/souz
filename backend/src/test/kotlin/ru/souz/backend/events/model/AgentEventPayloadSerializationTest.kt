package ru.souz.backend.events.model

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import ru.souz.llms.restJsonMapper

class AgentEventPayloadSerializationTest {
    @Test
    fun `storage codec round trips each typed payload`() {
        val payloads = listOf(
            AgentEventType.MESSAGE_CREATED to MessageCreatedPayload(
                messageId = UUID.fromString("11111111-1111-1111-1111-111111111111"),
                seq = 3L,
                role = "assistant",
                content = "Hello",
            ),
            AgentEventType.MESSAGE_DELTA to MessageDeltaPayload(
                messageId = UUID.fromString("22222222-2222-2222-2222-222222222222"),
                delta = "chunk",
            ),
            AgentEventType.MESSAGE_COMPLETED to MessageCompletedPayload(
                messageId = UUID.fromString("33333333-3333-3333-3333-333333333333"),
                seq = 4L,
                role = "assistant",
                content = "Completed",
            ),
            AgentEventType.EXECUTION_STARTED to ExecutionStartedPayload(
                executionId = UUID.fromString("44444444-4444-4444-4444-444444444444"),
                userMessageId = UUID.fromString("55555555-5555-5555-5555-555555555555"),
                model = "OpenAI-GPT-5.2",
                provider = "OPENAI",
                streamingMessages = true,
            ),
            AgentEventType.EXECUTION_FINISHED to ExecutionFinishedPayload(
                executionId = UUID.fromString("66666666-6666-6666-6666-666666666666"),
                assistantMessageId = UUID.fromString("77777777-7777-7777-7777-777777777777"),
                status = "completed",
                usage = ExecutionUsagePayload(
                    promptTokens = 10,
                    completionTokens = 5,
                    totalTokens = 15,
                    precachedTokens = 2,
                ),
            ),
            AgentEventType.EXECUTION_FAILED to ExecutionFailedPayload(
                executionId = UUID.fromString("88888888-8888-8888-8888-888888888888"),
                assistantMessageId = UUID.fromString("99999999-9999-9999-9999-999999999999"),
                errorCode = "agent_execution_failed",
                errorMessage = "boom",
            ),
            AgentEventType.EXECUTION_CANCELLED to ExecutionCancelledPayload(
                executionId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                assistantMessageId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
            ),
            AgentEventType.TOOL_CALL_STARTED to ToolCallStartedPayload(
                toolCallId = "tool-started",
                name = "SearchDocs",
                argumentKeys = listOf("query", "token"),
                argumentsPreview = restJsonMapper.readTree(
                    """
                    {
                      "query": "hello",
                      "token": "[REDACTED]",
                      "nested": {
                        "page": "1"
                      }
                    }
                    """.trimIndent()
                ),
            ),
            AgentEventType.TOOL_CALL_FINISHED to ToolCallFinishedPayload(
                toolCallId = "tool-finished",
                name = "SearchDocs",
                resultPreview = restJsonMapper.readTree("""{"items":["ok"]}"""),
                durationMs = 42L,
            ),
            AgentEventType.TOOL_CALL_FAILED to ToolCallFailedPayload(
                toolCallId = "tool-failed",
                name = "SearchDocs",
                error = "safe preview",
                durationMs = 7L,
            ),
            AgentEventType.OPTION_REQUESTED to ChoiceRequestedPayload(
                optionId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"),
                kind = "choice",
                title = "Pick one",
                selectionMode = "single",
                options = listOf(
                    ChoiceOptionItemPayload(id = "a", label = "Alpha", content = "first"),
                    ChoiceOptionItemPayload(id = "b", label = "Beta", content = null),
                ),
            ),
            AgentEventType.OPTION_ANSWERED to ChoiceAnsweredPayload(
                optionId = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd"),
                status = "answered",
                selectedOptionIds = listOf("a"),
                freeText = "because",
                metadata = mapOf("source" to "web-ui"),
            ),
        )

        payloads.forEach { (type, payload) ->
            assertEquals(
                expected = payload,
                actual = AgentEventPayloadStorageCodec.fromStorageJson(
                    type = type,
                    payload = AgentEventPayloadStorageCodec.toStorageJson(payload),
                ),
            )
        }
    }

    @Test
    fun `legacy string map payload with embedded json falls back to raw payload`() {
        val legacyPayload = restJsonMapper.readTree(
            """
            {
              "optionId": "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee",
              "kind": "choice",
              "title": "Pick one",
              "selectionMode": "single",
              "options": "[{\"id\":\"a\",\"label\":\"Alpha\",\"content\":\"first\"}]"
            }
            """.trimIndent()
        )

        val decoded = AgentEventPayloadStorageCodec.fromStorageJson(
            type = AgentEventType.OPTION_REQUESTED,
            payload = legacyPayload,
        )

        val rawPayload = assertIs<RawAgentEventPayload>(decoded)
        assertEquals("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee", rawPayload.values["optionId"])
        assertEquals(
            """[{"id":"a","label":"Alpha","content":"first"}]""",
            rawPayload.values["options"],
        )
    }
}
