package ru.souz.tool.memory

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import ru.souz.llms.LLMResponse
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.memory.CompletedTurnMemoryInput
import ru.souz.memory.ConversationMemoryRuntime
import ru.souz.memory.MemoryContext
import ru.souz.memory.NoopConversationMemoryRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolSearchMemoryTest {
    @Test
    fun `search maps invocation metadata and serializes structured facts`() = runTest {
        var capturedContext: MemoryContext? = null
        var capturedSemanticQuery: String? = null
        var capturedLexicalHints: List<String>? = null
        var capturedMaxFacts: Int? = null
        val tool = ToolSearchMemory(
            runtime { context, semanticQuery, lexicalHints, maxFacts ->
                capturedContext = context
                capturedSemanticQuery = semanticQuery
                capturedLexicalHints = lexicalHints
                capturedMaxFacts = maxFacts
                listOf(
                    ConversationMemoryRuntime.SearchFact(
                        "fact-1", "global", "PREFERENCE", "Tests first", "Tests first.", 0.87f
                    )
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

        assertEquals("owner-7", capturedContext?.ownerId?.value)
        assertEquals("conversation-9", capturedContext?.conversationId?.value)
        assertEquals("conversation-9", capturedContext?.sessionId?.value)
        assertEquals("User testing preferences", capturedSemanticQuery)
        assertEquals(listOf("tests first", "TDD"), capturedLexicalHints)
        assertEquals(4, capturedMaxFacts)
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
            var capturedHints: List<String>? = null
            var capturedMaxFacts: Int? = null
            val message = ToolSearchMemory(
                runtime { _, _, lexicalHints, maxFacts ->
                    capturedHints = lexicalHints
                    capturedMaxFacts = maxFacts
                    emptyList()
                }
            ).invoke(LLMResponse.FunctionCall(ToolSearchMemory.NAME, case.arguments))
            val error = restJsonMapper.readTree(message.content)["error"]

            if (case.expectedHints != null) {
                assertTrue(error.isNull, case.name)
                assertEquals(case.expectedHints, capturedHints, case.name)
                assertEquals(8, capturedMaxFacts, case.name)
            } else {
                assertEquals("invalid_arguments", error["code"]?.asText(), case.name)
                assertNull(capturedHints, case.name)
            }
        }
        assertEquals(
            listOf("semanticQuery", "lexicalHints"),
            ToolSearchMemory(runtime { _, _, _, _ -> emptyList() }).fn.parameters.required,
        )
    }

    @Test
    fun `unavailable and runtime failure return structured safe errors`() = runTest {
        val unavailable = ToolSearchMemory(NoopConversationMemoryRuntime).invoke(functionCall())
        val failed = ToolSearchMemory(runtime { _, _, _, _ ->
            error("sqlite failed at /private/user/memory.db")
        }).invoke(functionCall())

        assertEquals("memory_unavailable", restJsonMapper.readTree(unavailable.content)["error"]["code"].asText())
        restJsonMapper.readTree(failed.content)["error"].also { error ->
            assertEquals("search_failed", error["code"].asText())
            assertEquals("Memory search failed.", error["message"].asText())
        }
    }

    @Test
    fun `runtime cancellation propagates`() = runTest {
        assertFailsWith<CancellationException> {
            ToolSearchMemory(runtime { _, _, _, _ ->
                throw CancellationException("cancelled")
            }).invoke(functionCall())
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

    private fun runtime(
        search: suspend (MemoryContext, String, List<String>, Int) -> List<ConversationMemoryRuntime.SearchFact>
    ) =
        object : ConversationMemoryRuntime {
            override suspend fun searchMemory(
                context: MemoryContext,
                semanticQuery: String,
                lexicalHints: List<String>,
                maxFacts: Int,
            ): List<ConversationMemoryRuntime.SearchFact> =
                search(context, semanticQuery, lexicalHints, maxFacts)

            override suspend fun captureCompletedTurn(input: CompletedTurnMemoryInput) = Unit
        }
}
