package ru.souz.tool.memory

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import ru.souz.llms.LLMResponse
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.memory.CompletedTurnMemoryInput
import ru.souz.memory.ConversationMemoryRuntime
import ru.souz.memory.MemorySearchFact
import ru.souz.memory.MemorySearchRequest
import ru.souz.memory.MemorySearchResult
import ru.souz.memory.NoopConversationMemoryRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolSearchMemoryTest {
    @Test
    fun `search maps invocation metadata and serializes structured facts`() = runTest {
        var captured: MemorySearchRequest? = null
        val tool = ToolSearchMemory(
            runtime { request ->
                captured = request
                MemorySearchResult(
                    listOf(MemorySearchFact("fact-1", "global", "PREFERENCE", "Tests first", "Tests first.", 0.87f))
                )
            }
        )

        val message = tool.invoke(
            functionCall(
                "semanticQuery" to "User testing preferences",
                "lexicalHints" to listOf("tests first", "TDD"),
                "maxFacts" to 4,
            ),
            ToolInvocationMeta(
                userId = "owner-7",
                conversationId = "conversation-9",
                requestId = "request-3",
            ),
        )

        assertEquals("owner-7", captured?.context?.ownerId?.value)
        assertEquals("conversation-9", captured?.context?.conversationId?.value)
        assertEquals("conversation-9", captured?.context?.sessionId?.value)
        assertEquals("User testing preferences", captured?.semanticQuery)
        assertEquals(listOf("tests first", "TDD"), captured?.lexicalHints)
        assertEquals(4, captured?.maxFacts)
        restJsonMapper.readTree(message.content).also { body ->
            assertEquals("fact-1", body["facts"].single()["factId"].asText())
            assertEquals("global", body["facts"].single()["scope"].asText())
            assertTrue(body["error"].isNull)
        }
    }

    @Test
    fun `input validation requires and normalizes lexical hints`() = runTest {
        data class Case(val name: String, val arguments: Map<String, Any>, val expectedHints: List<String>? = null)
        val validArguments = mapOf(
            "semanticQuery" to "User preferences",
            "lexicalHints" to listOf("preferences"),
        )

        listOf(
            Case(
                "hints normalized",
                mapOf("semanticQuery" to "User preferences", "lexicalHints" to listOf(" tests ", "TDD", "tests")),
                listOf("tests", "TDD"),
            ),
            Case("hints omitted", mapOf("semanticQuery" to "User preferences")),
            Case("empty hints", mapOf("semanticQuery" to "User preferences", "lexicalHints" to emptyList<String>())),
            Case("missing query", emptyMap()),
            Case("blank query", mapOf("semanticQuery" to "  ")),
            Case("blank hint", mapOf("semanticQuery" to "User preferences", "lexicalHints" to listOf("tests", " "))),
            Case("too many hints", mapOf("semanticQuery" to "User preferences", "lexicalHints" to List(17) { "hint-$it" })),
            Case("limit below range", validArguments + ("maxFacts" to 0)),
            Case("limit above range", validArguments + ("maxFacts" to 17)),
            Case("non-integer limit", validArguments + ("maxFacts" to 2.5)),
        ).forEach { case ->
            var captured: MemorySearchRequest? = null
            val message = ToolSearchMemory(
                runtime { request ->
                    captured = request
                    MemorySearchResult()
                }
            ).invoke(LLMResponse.FunctionCall(ToolSearchMemory.NAME, case.arguments))
            val error = restJsonMapper.readTree(message.content)["error"]

            if (case.expectedHints != null) {
                assertTrue(error.isNull, case.name)
                assertEquals(case.expectedHints, captured?.lexicalHints, case.name)
                assertEquals(8, captured?.maxFacts, case.name)
            } else {
                assertEquals("invalid_arguments", error["code"]?.asText(), case.name)
                assertNull(captured, case.name)
            }
        }
        assertEquals(
            listOf("semanticQuery", "lexicalHints"),
            ToolSearchMemory(runtime { MemorySearchResult() }).fn.parameters.required,
        )
    }

    @Test
    fun `unavailable and runtime failure return structured safe errors`() = runTest {
        val unavailable = ToolSearchMemory(NoopConversationMemoryRuntime).invoke(functionCall())
        val failed = ToolSearchMemory(
            runtime { error("sqlite failed at /private/user/memory.db") }
        ).invoke(functionCall())

        assertEquals("memory_unavailable", restJsonMapper.readTree(unavailable.content)["error"]["code"].asText())
        restJsonMapper.readTree(failed.content)["error"].also { error ->
            assertEquals("search_failed", error["code"].asText())
            assertEquals("Memory search failed.", error["message"].asText())
        }
    }

    @Test
    fun `runtime cancellation propagates`() = runTest {
        assertFailsWith<CancellationException> {
            ToolSearchMemory(runtime { throw CancellationException("cancelled") }).invoke(functionCall())
        }
    }

    private fun functionCall(vararg arguments: Pair<String, Any>): LLMResponse.FunctionCall =
        LLMResponse.FunctionCall(
            name = ToolSearchMemory.NAME,
            arguments = mapOf(
                "semanticQuery" to "User testing preferences",
                "lexicalHints" to listOf("testing preferences"),
                *arguments,
            ),
        )

    private fun runtime(search: suspend (MemorySearchRequest) -> MemorySearchResult) =
        object : ConversationMemoryRuntime {
            override suspend fun searchMemory(request: MemorySearchRequest): MemorySearchResult = search(request)

            override suspend fun captureCompletedTurn(input: CompletedTurnMemoryInput) = Unit
        }
}
