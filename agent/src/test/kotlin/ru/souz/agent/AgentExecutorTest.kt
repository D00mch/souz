package ru.souz.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import ru.souz.agent.runtime.AgentRuntimeEvent
import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentSettings
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AgentExecutorTest {
    @Test
    fun `executor reads active run input capability from the selected agent`() {
        val agent = CapturingAgent()
        val executor = AgentExecutor(agentProvider = { agent })

        assertFalse(executor.supportsActiveRunInput(AgentId.GRAPH))

        agent.supportsActiveRunInput = true

        assertTrue(executor.supportsActiveRunInput(AgentId.GRAPH))
    }

    @Test
    fun `executor forwards explicit input submission to the selected agent`() = runTest {
        val agent = CapturingAgent().apply { acceptSubmissions = true }
        val executor = AgentExecutor(agentProvider = { agent })

        assertTrue(executor.submitToActiveRun(AgentId.SKILLS_GRAPH, "follow-up"))
        assertEquals(listOf("follow-up"), agent.submittedInputs)
    }

    @Test
    fun `executor prepares seed and forwards tracing without changing agent context`() = runTest {
        val agent = CapturingAgent()
        val executor = AgentExecutor(agentProvider = { agent })
        val eventSink = object : AgentRuntimeEventSink {
            override suspend fun emit(event: AgentRuntimeEvent) = Unit
        }
        val callback: GraphStepCallback = { _, _, _, _ -> }

        val result = executor.executeWithTrace(
            agentId = AgentId.GRAPH,
            context = baseContext(),
            input = "hello",
            eventSink = eventSink,
            onStep = callback,
        )

        val executedContext = agent.executedContexts.single()
        assertEquals("hello", executedContext.input)
        assertEquals("Base system prompt", executedContext.systemPrompt)
        assertSame(eventSink, executedContext.runtimeEventSink)
        assertSame(callback, agent.receivedCallback)
        assertEquals("assistant response", result.output)
        assertEquals("Base system prompt", result.context.systemPrompt)
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
    )

    private class CapturingAgent : TraceableAgent {
        override var supportsActiveRunInput: Boolean = false
        val executedContexts = mutableListOf<AgentContext<String>>()
        var receivedCallback: GraphStepCallback? = null
        var acceptSubmissions = false
        val submittedInputs = mutableListOf<String>()

        override val sideEffects: Flow<AgentStreamChunk> = emptyFlow()

        override suspend fun execute(ctx: AgentContext<String>): String = executeWithTrace(ctx).output

        override suspend fun executeWithTrace(
            ctx: AgentContext<String>,
            onStep: GraphStepCallback?,
        ): AgentExecutionResult {
            executedContexts += ctx
            receivedCallback = onStep
            return AgentExecutionResult(
                output = "assistant response",
                context = ctx.copy(input = "assistant response"),
            )
        }

        override suspend fun cancelActiveJob() = Unit

        override suspend fun submitToActiveRun(input: String): Boolean {
            submittedInputs += input
            return acceptSubmissions
        }
    }
}
