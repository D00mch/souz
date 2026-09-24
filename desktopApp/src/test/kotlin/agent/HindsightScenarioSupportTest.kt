package agent

import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.databind.node.ObjectNode
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import ru.souz.backend.memory.hindsight.HindsightConversationMemoryRuntime
import ru.souz.llms.http.providerHttpClientDefaults
import ru.souz.llms.restJsonMapper
import ru.souz.memory.CompletedTurnMemoryInput
import ru.souz.memory.ConversationId
import ru.souz.memory.MemoryContext
import ru.souz.memory.MemoryOwnerId
import ru.souz.memory.MemoryRetrievalRequest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HindsightScenarioSupportTest {
    private val context = MemoryContext(MemoryOwnerId("souz-memory-test-owner"), ConversationId("chat"), null, null)
    private val input = CompletedTurnMemoryInput(context, "chat", "message", "reply", "Budget 63000", "Noted")

    @Test
    fun `real adapter can consume observed responses and read-only probes never retain`() = runTest {
        val engine = MockEngine { request ->
            respond(
                when {
                    request.url.encodedPath.endsWith("/recall") ->
                        """{"results":[{"id":"fact","text":"Budget 63000","document_id":"souz-turn-message"}]}"""
                    request.url.encodedPath.contains("/documents/") -> """{"id":"souz-turn-message"}"""
                    else -> """{"success":true}"""
                },
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        HttpClient(engine) { providerHttpClientDefaults() }.use { client ->
            val trace = HindsightHttpTrace(client)
            val memory = ScenarioMemoryRuntime(HindsightConversationMemoryRuntime(client, "http://hindsight.test"))
            trace.createBank("http://hindsight.test", null, context.ownerId.value)
            assertEquals("PUT", engine.requestHistory.first().method.value)
            assertFailsWith<IllegalArgumentException> { trace.createBank("http://hindsight.test", null, "personal-bank") }
            memory.captureCompletedTurn(input)
            trace.verifyDocument("http://hindsight.test", null, context.ownerId.value, "souz-turn-message")
            memory.captureEnabled = false
            memory.captureCompletedTurn(input.copy(userMessageId = "probe"))
            val result = memory.retrieveMemory(MemoryRetrievalRequest(context, "budget"))
            assertEquals("fact", result.facts.single().factId)
            memory.searchMemory(context, "budget", emptyList(), 1)
            trace.requireHealthy()
            assertEquals(1, trace.snapshot().count { it.operation == "retain" })
            assertEquals(setOf("souz-turn-message"), sourceDocuments(trace.snapshot(), memory.factIds))
            assertEquals(listOf("souz-turn-message"), memory.capturedDocuments)
            assertTrue(memory.contextBlocks.any { "63000" in it })
            memory.resetProbe()
            assertTrue(memory.factIds.isEmpty() && memory.contextBlocks.isEmpty())
        }
    }

    @Test
    fun `swallowed retain and recall failures still fail the harness`() = runTest {
        for ((status, body) in listOf(HttpStatusCode.InternalServerError to "{}", HttpStatusCode.OK to """{"success":false}""")) {
            HttpClient(MockEngine {
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            }) { providerHttpClientDefaults() }.use { client ->
                val trace = HindsightHttpTrace(client)
                val runtime = HindsightConversationMemoryRuntime(client, "http://hindsight.test")
                runtime.captureCompletedTurn(input)
                assertFailsWith<IllegalStateException> { trace.requireHealthy() }
            }
        }
        HttpClient(MockEngine { throw java.io.IOException("sensitive transport detail") }) {
            providerHttpClientDefaults()
        }.use { client ->
            val trace = HindsightHttpTrace(client)
            HindsightConversationMemoryRuntime(client, "http://hindsight.test")
                .retrieveMemory(MemoryRetrievalRequest(context, "budget"))
            assertFailsWith<IllegalStateException> { trace.requireHealthy() }
            assertFalse(restJsonMapper.writeValueAsString(trace.snapshot()).contains("sensitive transport detail"))
        }
    }

    @Test
    fun `verification rejects a missing document even after successful retain`() = runTest {
        HttpClient(MockEngine {
            respond("{}", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { providerHttpClientDefaults() }.use { client ->
            assertFailsWith<IllegalStateException> {
                HindsightHttpTrace(client).verifyDocument("http://hindsight.test", null, "owner", "missing")
            }
        }
    }

    @Test
    fun `source attribution excludes unused candidates and follows observation evidence`() {
        val exchanges = listOf(MemoryHttpExchange("recall", 200, restJsonMapper.readTree("""
            {"results":[
              {"id":"observation","source_fact_ids":["source"]},
              {"id":"unused","document_id":"unrelated"}
            ],"source_facts":{"source":{"id":"source","document_id":"original"}}}
        """)))
        assertEquals(setOf("original"), sourceDocuments(exchanges, setOf("observation")))
        assertEquals(emptySet(), sourceDocuments(exchanges, emptySet()))
    }

    @Test
    fun `fixtures and manifest round trip preserve expected outcomes and refuse partial replay`() {
        val scenarios = loadMemoryScenarios()
        assertTrue(scenarios.probes.any { it.diagnostic && it.chat == "new-chat" })
        assertTrue(scenarios.episodes.any { episode -> episode.turns.any { it.toolResults.isNotEmpty() } })
        val manifest = MemoryManifest(
            corpusId = "corpus", settings = MemoryRunSettings("skills", "model"),
            owners = mapOf("main" to "souz-memory-test-main"), chats = mapOf("main" to "chat"), scenarios = scenarios,
        )
        val decoded = restJsonMapper.readValue<MemoryManifest>(restJsonMapper.writeValueAsString(manifest))
        assertEquals(manifest, decoded)
        assertFailsWith<IllegalArgumentException> { decoded.validateForReplay() }
        assertFailsWith<IllegalArgumentException> {
            decoded.copy(complete = true, owners = mapOf("main" to "real-user")).validateForReplay()
        }
    }

    @Test
    fun `source context and answer checks are independent and diagnostic failures stay visible`() {
        val turn = RecordedMemoryTurn("budget", "episode", "main", "chat", "document", "", "", "", "", emptyList())
        val probe = MemoryProbe(
            "latest", "budget?", sourceTurns = listOf("budget"), contextPatterns = listOf("63000"),
            answerPatterns = listOf("63000"), diagnostic = true,
        )
        val result = evaluateMemoryProbe(probe, listOf(turn), "63000", "old budget 48000", emptySet())
        assertEquals(listOf(false, false, true), result.checks.map { it.passed })
        assertFalse(result.passed)
        assertTrue(result.diagnostic)
        val isolation = evaluateMemoryProbe(
            probe.copy(sourceTurns = emptyList(), contextPatterns = emptyList(), forbiddenSourceOwner = "main"),
            listOf(turn), "63000", "63000", setOf("document"),
        )
        assertEquals(false, isolation.checks.last().passed)
    }

    @Test
    fun `scripted evaluation skips answers and checks the actual LLM memory context`() {
        val probe = MemoryProbe("hotel", "Which hotel?", contextPatterns = listOf("742"), answerPatterns = listOf("742"))
        val partial = evaluateMemoryProbe(probe, emptyList(), "fixed", "Hotel 742", emptySet(),
            MemoryAgentLlm.scripted, "Hotel 742")
        assertEquals("PARTIAL", partial.status)
        assertFalse(partial.passed)
        assertEquals(listOf("PASS", "PASS", "SKIPPED"), partial.checks.map { it.status })
        assertEquals("фиксированный ответ агента", partial.checks.last().reason)
        val missingAtLlm = evaluateMemoryProbe(probe, emptyList(), "fixed", "Hotel 742", emptySet(),
            MemoryAgentLlm.scripted, "")
        assertEquals("FAIL", missingAtLlm.status)
        val onlyAnswer = evaluateMemoryProbe(probe.copy(contextPatterns = emptyList()), emptyList(),
            "fixed", "", emptySet(), MemoryAgentLlm.scripted, "")
        assertEquals("SKIPPED", onlyAnswer.status)
        assertFalse(onlyAnswer.passed)
        val live = evaluateMemoryProbe(probe, emptyList(), "Hotel 742", "Hotel 742", emptySet())
        assertEquals("PASS", live.status)
        assertTrue(live.passed)

        val isolation = evaluateMemoryProbe(probe.copy(contextPatterns = emptyList(),
            forbiddenSourceOwner = "other", forbiddenAnswerPatterns = listOf("742")), emptyList(),
            "fixed", "Hotel 742", emptySet(), MemoryAgentLlm.scripted, "Hotel 742")
        assertEquals("FAIL", isolation.status)
        val film = RecordedMemoryTurn("film", "episode", "main", "chat", "doc", "", "", "", "Snatch", emptyList())
        val filmProbe = MemoryProbe("film", "Which film?", answerFromTurn = "film")
        assertEquals("FAIL", evaluateMemoryProbe(filmProbe, listOf(film), "Snatch", "", emptySet(),
            MemoryAgentLlm.scripted, "").status)
    }

    @Test
    fun `legacy manifests replay in either mode and reports disclose skipped checks`() {
        val scenarios = MemoryScenarios(listOf(MemoryEpisode("episode", turns = listOf(MemoryTurn("turn", "question")))),
            listOf(MemoryProbe("probe", "past?", answerPatterns = listOf("answer"))))
        val manifest = MemoryManifest(corpusId = "legacy", settings = MemoryRunSettings("skills", "model"),
            owners = mapOf("main" to "souz-memory-test-legacy"), chats = mapOf("main" to "chat"), scenarios = scenarios,
            turns = listOf(RecordedMemoryTurn("turn", "episode", "main", "chat", "doc", "", "", "question", "answer", emptyList())),
            complete = true)
        val tree = restJsonMapper.valueToTree<ObjectNode>(manifest)
        (tree.path("settings") as ObjectNode).remove("agentLlm")
        tree.path("scenarios").path("episodes")[0].path("turns").forEach {
            (it as ObjectNode).remove(listOf("assistant", "toolCalls"))
        }
        val legacy = restJsonMapper.readValue<MemoryManifest>(tree.toString())
        legacy.validateForReplay()
        assertEquals(MemoryAgentLlm.live, legacy.settings.agentLlm)
        assertEquals(MemoryAgentLlm.scripted, memoryAgentLlm(null))
        assertEquals(MemoryAgentLlm.live, memoryAgentLlm(" LIVE "))
        assertFailsWith<IllegalArgumentException> { memoryAgentLlm("unknown") }
        for (mode in MemoryAgentLlm.entries) {
            val report = evaluateMemoryProbe(legacy.scenarios.probes.single(), legacy.turns, "answer", "", emptySet(), mode)
            assertEquals(if (mode == MemoryAgentLlm.scripted) "SKIPPED" else "PASS", report.status)
            val text = memoryReport(legacy, legacy.settings.copy(agentLlm = mode), listOf(report))
            assertTrue(report.status in text)
            if (mode == MemoryAgentLlm.scripted) assertTrue("фиксированный ответ агента" in text)
        }
    }
}
