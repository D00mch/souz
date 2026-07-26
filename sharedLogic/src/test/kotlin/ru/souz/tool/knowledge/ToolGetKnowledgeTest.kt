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

        val response = ToolGetKnowledge(FakeKnowledgeStore(entry)).callBody()

        assertTrue(response["truncated"].asBoolean())
        assertEquals(8, response["storedLength"].asInt())
        response["head"].assertSegment("head", start = 0, end = 4)
        response["tail"].assertSegment("tail", start = 8, end = 12)
        assertEquals(4, response["omitted"]["start"].asInt())
        assertEquals(8, response["omitted"]["end"].asInt())
        assertNull(response["text"])
    }

    @Test
    fun `regex is case-sensitive by default and accepts inline flags`() = runTest {
        val tool = ToolGetKnowledge(FakeKnowledgeStore(complete("Alpha alpha")))

        val sensitive = tool.callBody(regex = "alpha", charsBefore = 0, charsAfter = 0)
        val insensitive = tool.callBody(regex = "(?i)alpha", charsBefore = 0, charsAfter = 0)

        assertEquals(listOf(6), sensitive["matches"].map { it["start"].asInt() })
        assertEquals(listOf(0, 6), insensitive["matches"].map { it["start"].asInt() })
    }

    @Test
    fun `regex matches are non-overlapping and include exact custom context offsets`() = runTest {
        val response = ToolGetKnowledge(FakeKnowledgeStore(complete("012aaaa789"))).callBody(
            regex = "aa",
            charsBefore = 2,
            charsAfter = 1,
        )

        val matches = response["matches"]
        assertEquals(2, matches.size())
        matches[0].assertMatch(
            text = "aa",
            start = 3,
            end = 5,
            excerpt = "12aaa",
            excerptStart = 1,
            excerptEnd = 6,
        )
        matches[1].assertMatch(
            text = "aa",
            start = 5,
            end = 7,
            excerpt = "aaaa7",
            excerptStart = 3,
            excerptEnd = 8,
        )
    }

    @Test
    fun `default regex context and match limit are applied`() = runTest {
        val contextText = "x".repeat(300) + "hit" + "y".repeat(300)
        val contextResponse = ToolGetKnowledge(FakeKnowledgeStore(complete(contextText))).callBody(regex = "hit")
        val match = contextResponse["matches"].single()
        assertEquals(44, match["excerptStart"].asInt())
        assertEquals(559, match["excerptEnd"].asInt())
        assertEquals(515, match["excerpt"].asText().length)

        val limitResponse = ToolGetKnowledge(
            FakeKnowledgeStore(complete(List(25) { "a" }.joinToString(" ")))
        ).callBody(regex = "a", charsBefore = 0, charsAfter = 0)
        assertEquals(ToolGetKnowledge.DEFAULT_MAX_MATCHES, limitResponse["matches"].size())
    }

    @Test
    fun `excerpt boundaries preserve whole Unicode code points with UTF-16 offsets`() = runTest {
        val response = ToolGetKnowledge(FakeKnowledgeStore(complete("a😀match😀z"))).callBody(
            regex = "match",
            charsBefore = 1,
            charsAfter = 1,
        )

        response["matches"].single().assertMatch(
            text = "match",
            start = 3,
            end = 8,
            excerpt = "😀match😀",
            excerptStart = 1,
            excerptEnd = 10,
        )
    }

    @Test
    fun `truncated text searches head and tail independently and rebases tail offsets`() = runTest {
        val entry = truncated(
            head = "alpha END",
            tail = "START omega",
            originalLength = 30,
        )
        val tool = ToolGetKnowledge(FakeKnowledgeStore(entry))

        val anchored = tool.callBody(
            regex = "END$|^START",
            charsBefore = 0,
            charsAfter = 0,
        )
        assertEquals(2, anchored["matches"].size())
        anchored["matches"][0].assertMatch("END", 6, 9, "END", 6, 9)
        anchored["matches"][1].assertMatch("START", 19, 24, "START", 19, 24)

        val acrossGap = tool.callBody(regex = "ENDSTART", charsBefore = 0, charsAfter = 0)
        assertTrue(acrossGap["matches"].isEmpty)
    }

    @Test
    fun `invalid argument combinations and bounds return structured errors without reading storage`() = runTest {
        val invalidArguments = listOf(
            emptyMap(),
            mapOf("knowledgeId" to " "),
            mapOf("knowledgeId" to 123),
            mapOf("knowledgeId" to KNOWLEDGE_ID, "unexpected" to true),
            mapOf("knowledgeId" to KNOWLEDGE_ID, "regex" to 123),
            mapOf("knowledgeId" to KNOWLEDGE_ID, "charsBefore" to 1),
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
            val response = ToolGetKnowledge(store).call(arguments).second
            assertEquals("invalid_arguments", response["error"]["code"].asText(), arguments.toString())
            assertEquals(0, store.getCalls)
        }
    }

    @Test
    fun `inclusive argument limits are accepted`() = runTest {
        val arguments = listOf(
            Triple(0, 0, 1),
            Triple(ToolGetKnowledge.MAX_CONTEXT_CHARS, ToolGetKnowledge.MAX_CONTEXT_CHARS, ToolGetKnowledge.MAX_MATCHES),
        )

        arguments.forEach { (before, after, matches) ->
            val response = ToolGetKnowledge(FakeKnowledgeStore(complete("x"))).callBody(
                regex = "x",
                charsBefore = before,
                charsAfter = after,
                maxMatches = matches,
            )
            assertEquals(1, response["matches"].size())
        }

        val bigIntegerResponse = ToolGetKnowledge(FakeKnowledgeStore(complete("x"))).call(
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

            val response = ToolGetKnowledge(store).callBody(regex = regex)

            assertEquals("invalid_regex", response["error"]["code"].asText())
            assertEquals(0, store.getCalls)
        }
    }

    @Test
    fun `missing conversation and failed storage reads return distinct structured errors`() = runTest {
        val missing = ToolGetKnowledge(FakeKnowledgeStore(null)).callBody()
        assertEquals("knowledge_not_found", missing["error"]["code"].asText())

        val unavailable = ToolGetKnowledge(
            FakeKnowledgeStore(error = KnowledgeStoreUnavailableException("conversation required"))
        ).callBody()
        assertEquals("conversation_unavailable", unavailable["error"]["code"].asText())
        assertEquals("conversation required", unavailable["error"]["message"].asText())

        val failed = ToolGetKnowledge(
            FakeKnowledgeStore(error = IllegalStateException("storage failed"))
        ).callBody()
        assertEquals("storage_failure", failed["error"]["code"].asText())
        assertEquals("storage failed", failed["error"]["message"].asText())
    }

    @Test
    fun `storage cancellation propagates`() = runTest {
        val tool = ToolGetKnowledge(FakeKnowledgeStore(error = CancellationException("cancelled")))

        assertFailsWith<CancellationException> { tool.callBody() }
    }

    private suspend fun ToolGetKnowledge.callBody(
        regex: String? = null,
        charsBefore: Int? = null,
        charsAfter: Int? = null,
        maxMatches: Int? = null,
    ): JsonNode {
        val arguments = buildMap<String, Any> {
            put("knowledgeId", KNOWLEDGE_ID)
            regex?.let { put("regex", it) }
            charsBefore?.let { put("charsBefore", it) }
            charsAfter?.let { put("charsAfter", it) }
            maxMatches?.let { put("maxMatches", it) }
        }
        return call(arguments).second
    }

    private suspend fun ToolGetKnowledge.call(
        arguments: Map<String, Any>,
        meta: ToolInvocationMeta = META,
    ) = invoke(
        LLMResponse.FunctionCall(name = ToolGetKnowledge.NAME, arguments = arguments),
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
