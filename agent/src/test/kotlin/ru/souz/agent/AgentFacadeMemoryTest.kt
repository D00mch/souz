package ru.souz.agent

import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import ru.souz.agent.runtime.AgentToolExecutor
import ru.souz.agent.session.GraphSessionService
import ru.souz.agent.spi.AgentSettingsProvider
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentSettings
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMRequest
import ru.souz.llms.ToolInvocationMeta
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentFacadeMemoryTest {
    @Test
    fun `executeForResult completes memory turn inside facade`() = runTest {
        var captureCount = 0
        val context = baseContext()
        val settingsProvider = mockk<AgentSettingsProvider>(relaxed = true)
        every { settingsProvider.activeAgentId } returns AgentId.GRAPH
        every { settingsProvider.gigaModel } returns LLMModel.Max
        val contextFactory = mockk<AgentContextFactory>()
        every { contextFactory.normalizeAgentId(any()) } returns AgentId.GRAPH
        every { contextFactory.create(AgentId.GRAPH) } returns context
        val executor = mockk<AgentExecutor>()
        every { contextFactory.availableAgents } returns listOf(AgentId.GRAPH)
        every { executor.sideEffects(any()) } returns emptyFlow()
        every { executor.cancelActiveJob(any()) } just runs
        coEvery { executor.executeWithTrace(any(), any(), any(), any(), any()) } returns AgentExecutionResult(
            output = "assistant response",
            context = context.copy(input = "assistant response"),
            captureCompletedTurn = { captureCount += 1 },
        )
        val sessionService = mockk<GraphSessionService>(relaxed = true)

        val facade = AgentFacade(
            settingsProvider = settingsProvider,
            contextFactory = contextFactory,
            executor = executor,
            sessionService = sessionService,
            agentToolExecutor = AgentToolExecutor(),
        )

        val result = facade.executeForResult("hello")

        assertEquals("assistant response", result.output)
        assertEquals(1, captureCount)
    }

    private fun baseContext(): AgentContext<String> = AgentContext(
        input = "",
        settings = AgentSettings(
            model = "model",
            temperature = 0f,
            toolsByCategory = emptyMap(),
        ),
        history = listOf(LLMRequest.Message(LLMMessageRole.system, "Base system prompt")),
        activeTools = emptyList(),
        systemPrompt = "Base system prompt",
        toolInvocationMeta = ToolInvocationMeta.localDefault(),
    )
}
