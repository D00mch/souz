package ru.souz.agent.scenarios

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import ru.souz.agent.runtime.AgentRuntimeEvent
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LlmProvider
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class AgentScenarioTestSupportTest {
    @Test
    fun `records real discovery execution history metrics and selected model settings`() = runTest {
        val tool = scenarioResultTool()
        val api = scriptedApi(
            reply("[\"scenario-smoke\"]"),
            call("GetSkillByName", mapOf("skillId" to "scenario-smoke")),
            call("GetSkillByName", mapOf("skillId" to "RecordScenarioResult")),
            call("RunSkillCommand", mapOf("skillId" to "RecordScenarioResult", "arguments" to mapOf(
                "requestId" to "CASE-42", "value" to "skill-framework-ok",
            ))),
            reply("Результат CASE-42 зарегистрирован."),
        )
        val reports = mutableListOf<String>()
        val result = AgentScenarioTestSupport("custom/model-version", api, "low", report = { reports += it }).runScenario(
            "Выполни тестовую процедуру для CASE-42.",
            mapOf("scenario-smoke" to "/skills/scenario-smoke/SKILL.md"), listOf(tool),
        )

        assertEquals("Результат CASE-42 зарегистрирован.", result.output)
        assertEquals(result.output, result.history.last().content)
        assertEquals(5, result.metrics.modelCalls)
        assertEquals(LLMResponse.Usage(15, 10, 25, 5), result.metrics.usage)
        assertEquals(listOf("GetSkillByName", "GetSkillByName", "RunSkillCommand"), result.metrics.toolCalls.map { it.name })
        assertEquals(3, result.events.filterIsInstance<AgentRuntimeEvent.ToolCallFinished>().size)
        assertTrue(result.metrics.duration.isPositive())
        assertTrue(result.requests.all { it.model == "custom/model-version" && it.provider == LlmProvider.OPENAI && it.reasoningEffort == "low" })
        assertTrue(result.requests.first().functions.isEmpty(), "Skill selection uses the selected execution model.")
        assertFalse(result.requests.take(2).any { request -> request.messages.any { "skill-framework-ok" in it.content } })
        assertTrue(result.requests[2].messages.any { "skill-framework-ok" in it.content })
        assertTrue(result.requests.drop(1).all { request -> request.messages.any { SCENARIO_DATE_LINE in it.content } })
        assertContains(reports.single(), "execution=completed")
        assertContains(reports.single(), "modelCalls=5")
        assertContains(reports.single(), "CASE-42")
        coVerify(exactly = 1) { tool.invoke(match { it.arguments["requestId"] == "CASE-42" }, any()) }
    }

    @Test
    fun `rejects truncated text and reports the incomplete execution`() = runTest {
        val reports = mutableListOf<String>()
        val api = scriptedApi(reply("Unfinished", LLMResponse.FinishReason.length))
        val failure = assertFailsWith<AssertionError> {
            AgentScenarioTestSupport("test-model", api, report = { reports += it }).runScenario("Do the task")
        }
        assertContains(failure.message.orEmpty(), "without a completed assistant response")
        assertContains(reports.single(), "execution=failed")
        assertContains(reports.single(), "modelCalls=1")
        assertContains(reports.single(), "totalTokens=5")
    }

    @Test
    fun `provider error is not mistaken for a successful final answer`() = runTest {
        val reports = mutableListOf<String>()
        val api = scriptedApi(LLMResponse.Chat.Error(503, "Provider unavailable"))
        val failure = assertFailsWith<AssertionError> {
            AgentScenarioTestSupport("test-model", api, report = { reports += it }).runScenario("Do the task")
        }
        assertContains(failure.message.orEmpty(), "Provider unavailable")
        assertContains(reports.single(), "execution=failed")
    }

    @Test
    fun `stops a tool loop before making more than the model call budget`() = runTest {
        val api = mockk<LLMChatAPI> {
            coEvery { message(any()) } returns call("GetSkillsNamesByCategory", mapOf("category" to "FILES"))
        }
        val reports = mutableListOf<String>()
        val failure = assertFailsWith<IllegalStateException> {
            AgentScenarioTestSupport("loop-model", api, maxModelCalls = 3, report = { reports += it }).runScenario("Keep going")
        }
        assertContains(failure.message.orEmpty(), "exceeded 3 model calls")
        coVerify(exactly = 3) { api.message(any()) }
        assertContains(reports.single(), "execution=failed")
        assertContains(reports.single(), "modelCalls=3")
        assertContains(reports.single(), "GetSkillsNamesByCategory")
    }

    @Test
    fun `wall time deadline cancels an in flight model request and still reports`() = runTest(timeout = 10.seconds) {
        var cancelled = false
        val api = mockk<LLMChatAPI> {
            coEvery { message(any()) } coAnswers {
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
        }
        val reports = mutableListOf<String>()
        assertFailsWith<TimeoutCancellationException> {
            AgentScenarioTestSupport("slow-model", api, scenarioTimeout = 2.seconds, report = { reports += it }).runScenario("Wait")
        }
        assertTrue(cancelled, "The deadline must cancel the provider request.")
        assertContains(reports.single(), "execution=failed")
        assertContains(reports.single(), "modelCalls=1")
    }

    @Test
    fun `successive scenarios isolate Skills tools history and metrics`() = runTest {
        val api = scriptedApi(
            reply("[\"scenario-smoke\"]"),
            call("GetSkillByName", mapOf("skillId" to "scenario-smoke")),
            reply("First complete"), reply("Second complete"),
        )
        val reports = mutableListOf<String>()
        val support = AgentScenarioTestSupport("same-model", api, report = { reports += it })
        val first = support.runScenario("First request", mapOf("scenario-smoke" to "/skills/scenario-smoke/SKILL.md"), listOf(scenarioResultTool()))
        val second = support.runScenario("Second request")

        assertEquals(3, first.metrics.modelCalls)
        assertEquals(1, second.metrics.modelCalls)
        assertEquals(LLMResponse.Usage(3, 2, 5, 1), second.metrics.usage)
        assertTrue(second.metrics.toolCalls.isEmpty())
        val messages = second.requests.single().messages
        assertFalse(messages.any { "First request" in it.content || "scenario-smoke" in it.content || "RecordScenarioResult" in it.content })
        assertTrue(messages.any { it.content == "Second request" })
        assertEquals(first.execution.context.systemPrompt, second.execution.context.systemPrompt)
        assertEquals(2, reports.size)
    }

    @Test
    fun `missing Skill resource fails before calling the model and emits diagnostics`() = runTest {
        val api = mockk<LLMChatAPI>()
        val reports = mutableListOf<String>()
        val failure = assertFailsWith<IllegalArgumentException> {
            AgentScenarioTestSupport("test-model", api, report = { reports += it })
                .runScenario("Do the task", mapOf("missing" to "/missing/SKILL.md"))
        }
        assertContains(failure.message.orEmpty(), "Missing Skill resource")
        coVerify(exactly = 0) { api.message(any()) }
        assertContains(reports.single(), "modelCalls=0")
        assertContains(reports.single(), "execution=failed")
    }
}

private fun scriptedApi(vararg responses: LLMResponse.Chat): LLMChatAPI {
    val remaining = ArrayDeque(responses.toList())
    return mockk { coEvery { message(any()) } answers { remaining.removeFirst() } }
}

private fun reply(text: String, finish: LLMResponse.FinishReason = LLMResponse.FinishReason.stop): LLMResponse.Chat.Ok =
    response(text, null, finish)

private fun call(name: String, arguments: Map<String, Any>): LLMResponse.Chat.Ok =
    response("", LLMResponse.FunctionCall(name, arguments), LLMResponse.FinishReason.function_call)

private fun response(text: String, call: LLMResponse.FunctionCall?, finish: LLMResponse.FinishReason): LLMResponse.Chat.Ok =
    LLMResponse.Chat.Ok(
        listOf(LLMResponse.Choice(LLMResponse.Message(text, LLMMessageRole.assistant, call, "call-${call?.name}"), 0, finish)),
        created = 1, model = "upstream-model-version", usage = LLMResponse.Usage(3, 2, 5, 1),
    )
