package ru.souz.agent.scenarios

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import ru.souz.agent.runtime.AgentRuntimeEvent
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMToolSetup
import ru.souz.tool.skills.ToolGetSkillByName
import ru.souz.tool.skills.ToolInvokeSkill
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class GraphAgentSkillScenariosIntegrationTest {
    @Test
    fun `model discovers the request procedure and records CASE-42`() = runTest(timeout = 5.minutes) {
        ScenarioLiveModel.fromEnvironment().use { model ->
            val tool = scenarioResultTool()
            val result = model.testSupport().runScenario(
                userPrompt = "Запиши результат проверки заявки CASE-42 в журнал.",
                skillResources = mapOf("scenario-smoke" to "/skills/scenario-smoke/SKILL.md"),
                mockedTools = listOf(tool),
            )

            coVerify(exactly = 1) {
                tool.invoke(match { it.arguments == mapOf("requestId" to "CASE-42", "value" to "skill-framework-ok") }, any())
            }
            coVerify(exactly = 1) { tool.invoke(any(), any()) }
            val loaded = result.events.indexOfFirst {
                it is AgentRuntimeEvent.ToolCallFinished && it.name == ToolGetSkillByName.NAME &&
                    it.result.toString().contains("\"skillId\":\"scenario-smoke\"") &&
                    it.result.toString().contains("skill-framework-ok")
            }
            val called = result.events.indexOfFirst {
                it is AgentRuntimeEvent.ToolCallStarted && it.name == ToolInvokeSkill.NAME &&
                    it.arguments["skillId"] == "RecordScenarioResult"
            }
            assertTrue(loaded >= 0 && called > loaded, "Read SKILL.md successfully before invoking the target tool.")
            assertEquals(
                mapOf("skillId" to "RecordScenarioResult", "arguments" to mapOf("requestId" to "CASE-42", "value" to "skill-framework-ok")),
                (result.events[called] as AgentRuntimeEvent.ToolCallStarted).arguments,
            )
            assertFalse(result.requests.first().messages.any { "skill-framework-ok" in it.content })
            assertEquals(model.modelId, result.metrics.model)
        }
    }
}

internal fun scenarioResultTool(): LLMToolSetup {
    val tool = mockk<LLMToolSetup>()
    every { tool.fn } returns LLMRequest.Function(
        "RecordScenarioResult", "Record the result of a procedure for a request.",
        LLMRequest.Parameters("object", mapOf(
            "requestId" to LLMRequest.Property("string", "Request identifier."),
            "value" to LLMRequest.Property("string", "Result value."),
        ), required = listOf("requestId", "value")),
    )
    coEvery { tool.invoke(any(), any()) } returns LLMRequest.Message(LLMMessageRole.function, """{"recorded":true}""")
    return tool
}
