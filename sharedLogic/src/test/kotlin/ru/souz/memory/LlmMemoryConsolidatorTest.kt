package ru.souz.memory

import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LlmMemoryConsolidatorTest {
    @Test
    fun `consolidator sends source fact ids and parses explicit coverage`() = runTest {
        val api = RecordingApi(
            okResponse(
                """
                [{
                  "kind":"PROJECT_DECISION",
                  "title":"Combined",
                  "body":"Compact replacement.",
                  "canonicalKey":null,
                  "confidence":0.9,
                  "importance":0.8,
                  "sourceFactIds":["fact-1","fact-2"],
                  "evidenceSourceEventIds":["source-1","source-2"]
                }]
                """.trimIndent()
            )
        )

        val candidates = LlmMemoryConsolidator(api, settingsProvider()).consolidate(
            input(modelAlias = LLMModel.LocalGemma4_E4B_It.alias)
        )

        assertEquals(listOf("fact-1", "fact-2"), candidates.single().sourceFactIds)
        assertEquals(LLMRequest.LocalOutputFormat.RAW, api.requests.single().localOutputFormat)
        assertEquals(LLMModel.LocalGemma4_E4B_It.alias, api.requests.single().model)
        val prompt = api.requests.single().messages.single { it.role == LLMMessageRole.user }.content
        assertTrue(prompt.contains("factId=fact-1"))
        assertTrue(prompt.contains("evidenceSourceEventIds=source-1"))
        val systemPrompt = api.requests.single().messages.single { it.role == LLMMessageRole.system }.content
        assertTrue(systemPrompt.contains("Omitted facts remain active"))
    }

    @Test
    fun `consolidator treats provider and malformed responses as retryable failures`() = runTest {
        val providerFailure = LlmMemoryConsolidator(
            RecordingApi(LLMResponse.Chat.Error(503, "unavailable")),
            settingsProvider(),
        )
        val malformed = LlmMemoryConsolidator(
            RecordingApi(okResponse("not-json"), okResponse("still-not-json")),
            settingsProvider(),
        )

        assertFailsWith<MemoryConsolidationException> { providerFailure.consolidate(input()) }
        assertFailsWith<MemoryConsolidationException> { malformed.consolidate(input()) }
    }

    @Test
    fun `consolidator immediately retries truncated json with larger budget`() = runTest {
        val api = RecordingApi(
            okResponse("[{\"title\":\"Combined\"", LLMResponse.FinishReason.length),
            okResponse(
                """
                [{
                  "kind":"PROJECT_DECISION",
                  "title":"Combined",
                  "body":"Compact replacement.",
                  "canonicalKey":null,
                  "confidence":0.9,
                  "sourceFactIds":["fact-1","fact-2"],
                  "evidenceSourceEventIds":["source-1","source-2"]
                }]
                """.trimIndent()
            ),
        )

        val candidates = LlmMemoryConsolidator(api, settingsProvider()).consolidate(input())

        assertEquals(1, candidates.size)
        assertEquals(listOf(1_200, 2_400), api.requests.map { it.maxTokens })
    }

    private fun input(modelAlias: String? = null): MemoryConsolidationInput = MemoryConsolidationInput(
        ownerId = MemoryOwnerId("owner"),
        scope = MemoryScope.project(ProjectId("project")),
        modelAlias = modelAlias,
        facts = listOf(
            details("fact-1", "source-1", "First fact"),
            details("fact-2", "source-2", "Second fact"),
        ),
    )

    private fun details(factId: String, sourceId: String, body: String): MemoryFactDetails {
        val scope = MemoryScope.project(ProjectId("project"))
        val fact = MemoryFact(
            id = factId,
            ownerId = MemoryOwnerId("owner"),
            scope = scope,
            kind = MemoryFactKind.PROJECT_DECISION,
            title = body,
            body = body,
            status = MemoryFactStatus.ACTIVE,
            confidence = 0.9f,
            pinned = false,
            createdBy = "writer",
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            supersedesFactId = null,
        )
        return MemoryFactDetails(
            fact,
            listOf(
                MemoryEvidenceDetail(
                    MemoryEvidence(factId, sourceId, body),
                    MemorySourceEvent(
                        id = sourceId,
                        ownerId = fact.ownerId,
                        scope = scope,
                        sourceType = "turn",
                        sourceRef = null,
                        text = body,
                        metadataJson = "{}",
                        createdAt = Instant.EPOCH,
                    ),
                )
            ),
        )
    }

    private fun settingsProvider(): SettingsProvider = mockk {
        every { gigaModel } returns LLMModel.AiTunnelGpt5Nano
    }

    private fun okResponse(
        content: String,
        finishReason: LLMResponse.FinishReason = LLMResponse.FinishReason.stop,
    ): LLMResponse.Chat.Ok = LLMResponse.Chat.Ok(
        choices = listOf(
            LLMResponse.Choice(
                message = LLMResponse.Message(content, LLMMessageRole.assistant, functionsStateId = null),
                index = 0,
                finishReason = finishReason,
            )
        ),
        created = 0,
        model = "test",
        usage = LLMResponse.Usage(0, 0, 0, 0),
    )

    private class RecordingApi(vararg responses: LLMResponse.Chat) : LLMChatAPI {
        private val responses = ArrayDeque(responses.toList())
        val requests = mutableListOf<LLMRequest.Chat>()

        override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat {
            requests += body
            return responses.removeFirstOrNull() ?: error("No recorded response")
        }

        override suspend fun messageStream(body: LLMRequest.Chat): Flow<LLMResponse.Chat> = emptyFlow()
        override suspend fun embeddings(body: LLMRequest.Embeddings): LLMResponse.Embeddings = error("unused")
        override suspend fun uploadFile(file: File): LLMResponse.UploadFile = error("unused")
        override suspend fun downloadFile(fileId: String): String? = error("unused")
        override suspend fun balance(): LLMResponse.Balance = error("unused")
    }
}
