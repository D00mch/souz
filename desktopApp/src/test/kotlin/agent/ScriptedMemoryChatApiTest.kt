package agent

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import ru.souz.agent.AgentId
import ru.souz.backend.memory.hindsight.HindsightConversationMemoryRuntime
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.http.providerHttpClientDefaults
import ru.souz.llms.restJsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScriptedMemoryChatApiTest {
    @ParameterizedTest
    @EnumSource(AgentId::class, names = ["GRAPH", "SKILLS_GRAPH"])
    fun `fixtures run through either graph and real adapter without a provider`(agent: AgentId) = runTest {
        val engine = MockEngine { request ->
            val body = if (request.url.encodedPath.endsWith("/recall")) {
                """{"results":[{"id":"fact","text":"September, Лазурный маяк-742","document_id":"source"}]}"""
            } else """{"success":true}"""
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        HttpClient(engine) { providerHttpClientDefaults() }.use { client ->
            val memory = ScenarioMemoryRuntime(HindsightConversationMemoryRuntime(client, "http://hindsight.test"))
            val api = ScriptedMemoryChatApi(agent)
            val support = AgentScenarioTestSupport(LLMModel.AnthropicHaiku45, agent, memory, backgroundScope, true)
            try {
                val scenarios = loadMemoryScenarios()
                var activeResults: Map<String, String> = emptyMap()
                val calls = mutableListOf<MemoryToolCall>()
                val toolNames = scenarios.episodes.flatMap { it.turns }.flatMap { it.toolResults.keys }.toSet()
                val di = support.createScenarioDi(toolNames.map { name -> object : LLMToolSetup {
                    override val fn = LLMRequest.Function(name)
                    override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message {
                        calls += MemoryToolCall(name, functionCall.arguments)
                        return LLMRequest.Message(LLMMessageRole.function, activeResults.getValue(name), name = name)
                    }
                } }, api)
                for (episode in scenarios.episodes) {
                    var history = emptyList<LLMRequest.Message>()
                    for (turn in episode.turns) {
                        calls.clear()
                        activeResults = turn.toolResults
                        api.beginTurn(turn)
                        val result = support.runConversationTurn(di, turn.text, history, meta(turn.id))
                        api.verifyComplete()
                        backgroundScope.coroutineContext[Job]!!.children.toList().joinAll()
                        assertEquals(turn.assistant, result.output)
                        assertEquals(turn.toolCalls, calls)
                        assertTrue(api.chatRequests.last().messages.containsAll(history.filter { it.role == LLMMessageRole.assistant }))
                        assertTrue("souz-turn-${turn.id}" in memory.capturedDocuments)
                        history = result.context.history
                    }
                }
                val retains = engine.requestHistory.filter { it.url.encodedPath.endsWith("/memories") }
                assertEquals(scenarios.episodes.sumOf { it.turns.size }, retains.size)
                val retainedWeather = restJsonMapper.readTree(retains.single {
                    restJsonMapper.readTree(it.body.toByteArray()).path("items")[0].path("document_id").asText() == "souz-turn-weather-tool"
                }.body.toByteArray()).path("items")[0].path("content").asText()
                assertTrue("17" in retainedWeather && "TOOL_OUTPUT" in retainedWeather)

                memory.captureEnabled = false
                memory.resetProbe()
                val probe = scenarios.probes.first()
                api.beginProbe(probe)
                support.runConversationTurn(di, probe.question, meta = meta("probe"))
                api.verifyComplete()
                backgroundScope.coroutineContext[Job]!!.children.toList().joinAll()
                assertTrue(memory.contextBlocks.all { it in api.memoryContext() })
                assertTrue("742" in api.memoryContext())
                assertFalse(api.chatRequests.last().messages.any { it.role == LLMMessageRole.assistant })
                assertEquals(retains.size, engine.requestHistory.count { it.url.encodedPath.endsWith("/memories") })
            } finally {
                support.finish()
            }
        }
    }

    @Test
    fun `scripts reject extra missing and unexpected requests including summarization`() = runTest {
        val turn = MemoryTurn("test", "question", assistant = "answer")
        val body = LLMRequest.Chat(messages = listOf(LLMRequest.Message(LLMMessageRole.user, turn.text)))
        val api = ScriptedMemoryChatApi(AgentId.SKILLS_GRAPH)
        api.beginTurn(turn)
        assertFailsWith<IllegalStateException> { api.verifyComplete() }
        api.message(body)
        api.verifyComplete()
        assertFailsWith<IllegalStateException> { api.message(body) }
        assertFailsWith<IllegalStateException> { api.verifyComplete() }
        for (unexpected in listOf(body.copy(isSummarization = true), body.copy(stream = true),
            body.copy(messages = emptyList()))) {
            val fresh = ScriptedMemoryChatApi(AgentId.SKILLS_GRAPH)
            fresh.beginTurn(turn)
            assertFailsWith<IllegalStateException> { fresh.message(unexpected) }
            assertFailsWith<IllegalStateException> { fresh.verifyComplete() }
        }
        val graph = ScriptedMemoryChatApi(AgentId.GRAPH)
        graph.beginTurn(turn)
        assertFailsWith<IllegalStateException> { graph.message(body) }
        assertFailsWith<IllegalStateException> { graph.verifyComplete() }
    }

    private fun meta(id: String) = ToolInvocationMeta(
        "souz-memory-test-owner", "chat", "request-$id", attributes = mapOf("userMessageId" to id),
    )
}
