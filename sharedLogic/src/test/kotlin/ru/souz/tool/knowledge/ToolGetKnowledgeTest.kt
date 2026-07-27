package ru.souz.tool.knowledge

import com.fasterxml.jackson.databind.JsonNode
import java.math.BigInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import ru.souz.agent.knowledge.ConversationKnowledgeStore
import ru.souz.agent.knowledge.KnowledgeContent
import ru.souz.agent.knowledge.KnowledgeEntry
import ru.souz.agent.knowledge.KnowledgeStoreUnavailableException
import ru.souz.agent.knowledge.KnowledgeWriteResult
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolGetKnowledgeTest {
    @Test
    fun `full read returns complete text and metadata`() = runTest {
        val entry = complete("hello")
        val store = FakeKnowledgeStore(entry)
        val tool = ToolGetKnowledge(store)
        val meta = ToolInvocationMeta(userId = "user-1", conversationId = "conversation-1")

        val (message, response) = tool.call(mapOf("knowledgeId" to KNOWLEDGE_ID), meta)

        assertEquals(LLMMessageRole.function, message.role)
        assertEquals(ToolGetKnowledge.NAME, message.name)
        assertEquals(KNOWLEDGE_ID, response["knowledgeId"].asText())
        assertEquals("RunSkillCommand", response["sourceTool"].asText())
        assertEquals(5, response["originalLength"].asInt())
        assertEquals(5, response["storedLength"].asInt())
        assertFalse(response["truncated"].asBoolean())
        assertEquals("hello", response["text"].asText())
        assertNull(response["head"])
        assertEquals(meta, store.lastMeta)
        assertEquals(KNOWLEDGE_ID, store.lastKnowledgeId)
    }

    @Test
    fun `full read reports retained and omitted ranges for truncated text`() = runTest {
        val entry = truncated(head = "head", tail = "tail", originalLength = 12)

        val response = ToolGetKnowledge(FakeKnowledgeStore(entry)).getBody()

        assertTrue(response["truncated"].asBoolean())
        assertEquals(8, response["storedLength"].asInt())
        response["head"].assertSegment("head", start = 0, end = 4)
        response["tail"].assertSegment("tail", start = 8, end = 12)
        assertEquals(4, response["omitted"]["start"].asInt())
        assertEquals(8, response["omitted"]["end"].asInt())
        assertNull(response["text"])
    }

    @Test
    fun `full read rejects search arguments without reading storage`() = runTest {
        val invalidArguments = listOf(
            emptyMap(),
            mapOf("knowledgeId" to " "),
            mapOf("knowledgeId" to 123),
            mapOf("knowledgeId" to KNOWLEDGE_ID, "unexpected" to true),
            mapOf("knowledgeId" to KNOWLEDGE_ID, "regex" to ".*"),
        )

        invalidArguments.forEach { arguments ->
            val store = FakeKnowledgeStore(complete("x"))
            val response = ToolGetKnowledge(store).call(arguments).second
            assertEquals("invalid_arguments", response["error"]["code"].asText(), arguments.toString())
            assertEquals(0, store.getCalls)
        }
    }

    @Test
    fun `search is case-sensitive by default and accepts inline flags`() = runTest {
        val tool = ToolSearchKnowledge(FakeKnowledgeStore(complete("Alpha alpha")))

        val sensitive = tool.searchBody(regex = "alpha", charsBefore = 0, charsAfter = 0)
        val insensitive = tool.searchBody(regex = "(?i)alpha", charsBefore = 0, charsAfter = 0)

        assertEquals(listOf(6), sensitive["matches"].map { it["start"].asInt() })
        assertEquals(listOf(0, 6), insensitive["matches"].map { it["start"].asInt() })
    }

    @Test
    fun `search matches are non-overlapping and include exact custom context offsets`() = runTest {
        val response = ToolSearchKnowledge(FakeKnowledgeStore(complete("012aaaa789"))).searchBody(
            regex = "aa",
            charsBefore = 2,
            charsAfter = 1,
        )

        val matches = response["matches"]
        assertEquals(2, matches.size())
        matches[0].assertMatch("aa", 3, 5, "12aaa", 1, 6)
        matches[1].assertMatch("aa", 5, 7, "aaaa7", 3, 8)
    }

    @Test
    fun `search omits excerpt when it is identical to the exact match`() = runTest {
        val match = ToolSearchKnowledge(FakeKnowledgeStore(complete("a target z")))
            .searchBody(regex = "target", charsBefore = 0, charsAfter = 0)
            .get("matches")
            .single()

        assertEquals("target", match["text"].asText())
        assertEquals(2, match["start"].asInt())
        assertEquals(8, match["end"].asInt())
        assertNull(match.get("excerpt"))
        assertNull(match.get("excerptStart"))
        assertNull(match.get("excerptEnd"))
    }

    @Test
    fun `default search context and match limit are applied`() = runTest {
        val contextText = "x".repeat(300) + "hit" + "y".repeat(300)
        val contextResponse = ToolSearchKnowledge(FakeKnowledgeStore(complete(contextText)))
            .searchBody(regex = "hit")
        val match = contextResponse["matches"].single()
        assertEquals(44, match["excerptStart"].asInt())
        assertEquals(559, match["excerptEnd"].asInt())
        assertEquals(515, match["excerpt"].asText().length)

        val limitResponse = ToolSearchKnowledge(
            FakeKnowledgeStore(complete(List(25) { "a" }.joinToString(" ")))
        ).searchBody(regex = "a", charsBefore = 0, charsAfter = 0)
        assertEquals(ToolSearchKnowledge.DEFAULT_MAX_MATCHES, limitResponse["matches"].size())
    }

    @Test
    fun `excerpt boundaries preserve whole Unicode code points with UTF-16 offsets`() = runTest {
        val response = ToolSearchKnowledge(FakeKnowledgeStore(complete("a😀match😀z"))).searchBody(
            regex = "match",
            charsBefore = 1,
            charsAfter = 1,
        )

        response["matches"].single().assertMatch("match", 3, 8, "😀match😀", 1, 10)
    }

    @Test
    fun `truncated text searches head and tail independently and rebases tail offsets`() = runTest {
        val entry = truncated(
            head = "alpha END",
            tail = "START omega",
            originalLength = 30,
        )
        val tool = ToolSearchKnowledge(FakeKnowledgeStore(entry))

        val anchored = tool.searchBody(
            regex = "END$|^START",
            charsBefore = 0,
            charsAfter = 0,
        )
        assertEquals(2, anchored["matches"].size())
        anchored["matches"][0].assertExactMatch("END", 6, 9)
        anchored["matches"][1].assertExactMatch("START", 19, 24)

        val acrossGap = tool.searchBody(regex = "ENDSTART", charsBefore = 0, charsAfter = 0)
        assertTrue(acrossGap["matches"].isEmpty)
    }

    @Test
    fun `invalid search arguments and bounds return structured errors without reading storage`() = runTest {
        val invalidArguments = listOf(
            emptyMap(),
            mapOf("knowledgeId" to " ", "regex" to "x"),
            mapOf("knowledgeId" to 123, "regex" to "x"),
            mapOf("knowledgeId" to KNOWLEDGE_ID),
            mapOf("knowledgeId" to KNOWLEDGE_ID, "regex" to " "),
            mapOf("knowledgeId" to KNOWLEDGE_ID, "regex" to 123),
            mapOf("knowledgeId" to KNOWLEDGE_ID, "regex" to "x", "unexpected" to true),
            mapOf("knowledgeId" to KNOWLEDGE_ID, "regex" to "x", "charsBefore" to 1.0),
            mapOf("knowledgeId" to KNOWLEDGE_ID, "regex" to "x", "charsAfter" to "1"),
            mapOf("knowledgeId" to KNOWLEDGE_ID, "regex" to "x", "maxMatches" to 1.9),
            mapOf("knowledgeId" to KNOWLEDGE_ID, "regex" to "x", "charsBefore" to -1),
            mapOf("knowledgeId" to KNOWLEDGE_ID, "regex" to "x", "charsBefore" to 4097),
            mapOf("knowledgeId" to KNOWLEDGE_ID, "regex" to "x", "charsAfter" to -1),
            mapOf("knowledgeId" to KNOWLEDGE_ID, "regex" to "x", "charsAfter" to 4097),
            mapOf("knowledgeId" to KNOWLEDGE_ID, "regex" to "x", "maxMatches" to 0),
            mapOf("knowledgeId" to KNOWLEDGE_ID, "regex" to "x", "maxMatches" to 101),
        )

        invalidArguments.forEach { arguments ->
            val store = FakeKnowledgeStore(complete("x"))
            val response = ToolSearchKnowledge(store).call(arguments).second
            assertEquals("invalid_arguments", response["error"]["code"].asText(), arguments.toString())
            assertEquals(0, store.getCalls)
        }
    }

    @Test
    fun `inclusive search limits are accepted`() = runTest {
        val arguments = listOf(
            Triple(0, 0, 1),
            Triple(
                ToolSearchKnowledge.MAX_CONTEXT_CHARS,
                ToolSearchKnowledge.MAX_CONTEXT_CHARS,
                ToolSearchKnowledge.MAX_MATCHES,
            ),
        )

        arguments.forEach { (before, after, matches) ->
            val response = ToolSearchKnowledge(FakeKnowledgeStore(complete("x"))).searchBody(
                regex = "x",
                charsBefore = before,
                charsAfter = after,
                maxMatches = matches,
            )
            assertEquals(1, response["matches"].size())
        }

        val bigIntegerResponse = ToolSearchKnowledge(FakeKnowledgeStore(complete("x"))).call(
            mapOf(
                "knowledgeId" to KNOWLEDGE_ID,
                "regex" to "x",
                "maxMatches" to BigInteger.ONE,
            )
        ).second
        assertEquals(1, bigIntegerResponse["matches"].size())
    }

    @Test
    fun `unsupported RE2 syntax returns invalid regex without reading storage`() = runTest {
        listOf("(?=x)", "(x)\\1").forEach { regex ->
            val store = FakeKnowledgeStore(complete("xx"))

            val response = ToolSearchKnowledge(store).searchBody(regex = regex)

            assertEquals("invalid_regex", response["error"]["code"].asText())
            assertEquals(0, store.getCalls)
        }
    }

    @Test
    fun `shared retrieval maps missing conversation and storage failures`() = runTest {
        listOf(
            FakeKnowledgeStore(null) to "knowledge_not_found",
            FakeKnowledgeStore(error = KnowledgeStoreUnavailableException("conversation required")) to
                "conversation_unavailable",
            FakeKnowledgeStore(error = IllegalStateException("storage failed")) to "storage_failure",
        ).forEach { (store, expectedCode) ->
            val retriever = KnowledgeRetriever(store)
            val getResponse = ToolGetKnowledge(retriever).getBody()
            val searchResponse = ToolSearchKnowledge(retriever).searchBody(regex = "target")

            assertEquals(expectedCode, getResponse["error"]["code"].asText())
            assertEquals(expectedCode, searchResponse["error"]["code"].asText())
        }
    }

    @Test
    fun `shared retrieval propagates storage cancellation`() = runTest {
        val retriever = KnowledgeRetriever(
            FakeKnowledgeStore(error = CancellationException("cancelled"))
        )

        assertFailsWith<CancellationException> { ToolGetKnowledge(retriever).getBody() }
        assertFailsWith<CancellationException> {
            ToolSearchKnowledge(retriever).searchBody(regex = "target")
        }
    }

    private suspend fun ToolGetKnowledge.getBody(): JsonNode =
        call(mapOf("knowledgeId" to KNOWLEDGE_ID)).second

    private suspend fun ToolSearchKnowledge.searchBody(
        regex: String,
        charsBefore: Int? = null,
        charsAfter: Int? = null,
        maxMatches: Int? = null,
    ): JsonNode {
        val arguments = buildMap<String, Any> {
            put("knowledgeId", KNOWLEDGE_ID)
            put("regex", regex)
            charsBefore?.let { put("charsBefore", it) }
            charsAfter?.let { put("charsAfter", it) }
            maxMatches?.let { put("maxMatches", it) }
        }
        return call(arguments).second
    }

    private suspend fun LLMToolSetup.call(
        arguments: Map<String, Any>,
        meta: ToolInvocationMeta = META,
    ) = invoke(
        LLMResponse.FunctionCall(name = fn.name, arguments = arguments),
        meta,
    ).let { message -> message to restJsonMapper.readTree(message.content) }

    private fun complete(text: String): KnowledgeEntry = KnowledgeEntry(
        id = KNOWLEDGE_ID,
        sourceTool = "RunSkillCommand",
        originalLength = text.length,
        content = KnowledgeContent.Complete(text),
    )

    private fun truncated(
        head: String,
        tail: String,
        originalLength: Int,
    ): KnowledgeEntry = KnowledgeEntry(
        id = KNOWLEDGE_ID,
        sourceTool = "RunSkillCommand",
        originalLength = originalLength,
        content = KnowledgeContent.Truncated(head, tail),
    )

    private companion object {
        const val KNOWLEDGE_ID = "550e8400-e29b-41d4-a716-446655440000"
        val META = ToolInvocationMeta(userId = "user-1", conversationId = "conversation-1")
    }
}

private class FakeKnowledgeStore(
    private val entry: KnowledgeEntry? = null,
    private val error: Exception? = null,
) : ConversationKnowledgeStore {
    var getCalls: Int = 0
        private set
    var lastMeta: ToolInvocationMeta? = null
        private set
    var lastKnowledgeId: String? = null
        private set

    override suspend fun put(
        meta: ToolInvocationMeta,
        sourceTool: String,
        content: String,
    ): KnowledgeWriteResult = error("Not used")

    override suspend fun get(
        meta: ToolInvocationMeta,
        knowledgeId: String,
    ): KnowledgeEntry? {
        getCalls++
        lastMeta = meta
        lastKnowledgeId = knowledgeId
        error?.let { throw it }
        return entry
    }

    override suspend fun clearConversation(meta: ToolInvocationMeta) = Unit
}

private fun JsonNode.assertSegment(
    text: String,
    start: Int,
    end: Int,
) {
    assertEquals(text, get("text").asText())
    assertEquals(start, get("start").asInt())
    assertEquals(end, get("end").asInt())
}

private fun JsonNode.assertMatch(
    text: String,
    start: Int,
    end: Int,
    excerpt: String,
    excerptStart: Int,
    excerptEnd: Int,
) {
    assertEquals(text, get("text").asText())
    assertEquals(start, get("start").asInt())
    assertEquals(end, get("end").asInt())
    assertEquals(excerpt, get("excerpt").asText())
    assertEquals(excerptStart, get("excerptStart").asInt())
    assertEquals(excerptEnd, get("excerptEnd").asInt())
}

private fun JsonNode.assertExactMatch(
    text: String,
    start: Int,
    end: Int,
) {
    assertEquals(text, get("text").asText())
    assertEquals(start, get("start").asInt())
    assertEquals(end, get("end").asInt())
    assertNull(get("excerpt"))
}
