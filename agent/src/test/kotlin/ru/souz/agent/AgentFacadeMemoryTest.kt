package ru.souz.agent

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import ru.souz.agent.memory.MemoryFactRecord
import ru.souz.agent.memory.MemoryScope
import ru.souz.agent.memory.MemoryScopeType
import ru.souz.agent.memory.MemoryTriggerType
import ru.souz.agent.memory.MemoryWriteInput
import ru.souz.agent.memory.MemoryWriteResult
import ru.souz.agent.memory.MemoryWriteService
import ru.souz.agent.spi.AgentSettingsProvider
import ru.souz.agent.session.GraphSessionService
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentSettings
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.toSystemPromptMessage
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentFacadeMemoryTest {
    @Test
    fun `post turn write sees updated context after successful execution`() = runTest {
        val settingsProvider = mockk<AgentSettingsProvider>(relaxed = true) {
            every { activeAgentId } returns AgentId.default
            every { gigaModel } returns LLMModel.Max
            every { contextSize } returns 24_000
            every { temperature } returns 0.6f
            every { regionProfile } returns "ru"
        }
        val initialContext = context(
            history = listOf("system".toSystemPromptMessage()),
        )
        val updatedContext = initialContext.copy(
            history = initialContext.history + LLMRequest.Message(LLMMessageRole.assistant, "Write tests first."),
        )
        val expectedContext = updatedContext.copy(
            toolInvocationMeta = updatedContext.toolInvocationMeta.copy(conversationId = "session-1"),
        )
        val contextFactory = mockk<AgentContextFactory> {
            every { normalizeAgentId(any()) } returns AgentId.default
            every { create(any()) } returns initialContext
        }
        val executor = mockk<AgentExecutor> {
            every { availableAgents } returns listOf(AgentId.default)
            every { sideEffects(any()) } returns emptyFlow()
            every { cancelActiveJob(any()) } returns Unit
            coEvery {
                executeWithTrace(
                    agentId = AgentId.default,
                    context = any(),
                    input = "Implement memory wiring",
                    eventSink = null,
                    onStep = any(),
                )
            } returns AgentExecutionResult(
                output = "Write tests first.",
                context = updatedContext,
            )
        }
        val sessionService = mockk<GraphSessionService>(relaxed = true) {
            every { currentSessionId() } returns "session-1"
        }
        val writeService = RecordingWriteService()
        val agentFacade = AgentFacade(
            settingsProvider = settingsProvider,
            contextFactory = contextFactory,
            executor = executor,
            sessionService = sessionService,
            agentToolExecutor = mockk(relaxed = true),
            memoryWriteService = writeService,
        )
        writeService.currentContextProvider = { agentFacade.currentContext.value }

        val response = agentFacade.execute("Implement memory wiring")

        assertEquals("Write tests first.", response)
        assertEquals(expectedContext, agentFacade.currentContext.value)
        assertEquals(expectedContext, writeService.contextSeenDuringWrite)
        assertEquals("session-1", writeService.inputs.single().turnRef)
        assertEquals(MemoryScope(MemoryScopeType.THREAD, "session-1"), writeService.inputs.single().scope)
        assertEquals(MemoryTriggerType.TASK_STATE_CHANGE, writeService.inputs.single().triggerType)
    }

    @Test
    fun `write failure does not fail agent response`() = runTest {
        val settingsProvider = mockk<AgentSettingsProvider>(relaxed = true) {
            every { activeAgentId } returns AgentId.default
            every { gigaModel } returns LLMModel.Max
            every { contextSize } returns 24_000
            every { temperature } returns 0.6f
            every { regionProfile } returns "ru"
        }
        val initialContext = context(history = listOf("system".toSystemPromptMessage()))
        val updatedContext = initialContext.copy(
            history = initialContext.history + LLMRequest.Message(LLMMessageRole.assistant, "Response survives."),
        )
        val expectedContext = updatedContext.copy(
            toolInvocationMeta = updatedContext.toolInvocationMeta.copy(conversationId = "session-2"),
        )
        val contextFactory = mockk<AgentContextFactory> {
            every { normalizeAgentId(any()) } returns AgentId.default
            every { create(any()) } returns initialContext
        }
        val executor = mockk<AgentExecutor> {
            every { availableAgents } returns listOf(AgentId.default)
            every { sideEffects(any()) } returns emptyFlow()
            every { cancelActiveJob(any()) } returns Unit
            coEvery {
                executeWithTrace(
                    agentId = AgentId.default,
                    context = any(),
                    input = "Keep going",
                    eventSink = null,
                    onStep = any(),
                )
            } returns AgentExecutionResult(
                output = "Response survives.",
                context = updatedContext,
            )
        }
        val sessionService = mockk<GraphSessionService>(relaxed = true) {
            every { currentSessionId() } returns "session-2"
        }
        val writeService = mockk<MemoryWriteService> {
            coEvery { write(any()) } throws IllegalStateException("memory write failed")
        }
        val agentFacade = AgentFacade(
            settingsProvider = settingsProvider,
            contextFactory = contextFactory,
            executor = executor,
            sessionService = sessionService,
            agentToolExecutor = mockk(relaxed = true),
            memoryWriteService = writeService,
        )

        val response = agentFacade.execute("Keep going")

        assertEquals("Response survives.", response)
        assertEquals(expectedContext, agentFacade.currentContext.value)
        coVerify(exactly = 1) { writeService.write(any()) }
    }

    private fun context(history: List<LLMRequest.Message>): AgentContext<String> =
        AgentContext(
            input = "",
            settings = AgentSettings(
                model = "test-model",
                temperature = 0.2f,
                toolsByCategory = emptyMap(),
            ),
            history = history,
            activeTools = emptyList(),
            systemPrompt = "system",
        )
}

private class RecordingWriteService : MemoryWriteService {
    val inputs = mutableListOf<MemoryWriteInput>()
    var currentContextProvider: (() -> AgentContext<String>)? = null
    var contextSeenDuringWrite: AgentContext<String>? = null

    override suspend fun write(input: MemoryWriteInput): MemoryWriteResult {
        inputs += input
        contextSeenDuringWrite = currentContextProvider?.invoke()
        return MemoryWriteResult(acceptedFacts = emptyList<MemoryFactRecord>())
    }
}
