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
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AgentExecutorTest {
    @Test
    fun `executor exposes the exact active mailbox instead of proxying submissions`() = runTest {
        val agent = CapturingAgent()
        val executor = AgentExecutor(agentProvider = { agent })
        val input = ActiveRunInput(
            history = listOf(LLMRequest.Message(LLMMessageRole.assistant, "client answer")),
            input = "continue",
        )
        var readyMailbox: ActiveRunMailbox? = null

        executor.execute(
            agentId = AgentId.SKILLS_GRAPH,
            context = baseContext(),
            input = "hello",
            onActiveRunReady = { mailbox ->
                readyMailbox = mailbox
                assertTrue(mailbox.submit { input })
            },
        )

        assertSame(agent.mailbox, readyMailbox)
        assertEquals(listOf(input), agent.receivedInputs)
    }

    @Test
    fun `executor prepares seed and forwards fixed history source and tracing`() = runTest {
        val agent = CapturingAgent()
        val eventSink = object : AgentRuntimeEventSink {
            override suspend fun emit(event: AgentRuntimeEvent) = Unit
        }
        val callback: GraphStepCallback = { _, _, _, _ -> }
        val executor = AgentExecutor(agentProvider = { agent }, onStep = callback)
        val history = listOf(LLMRequest.Message(LLMMessageRole.user, "pending history"))

        val result = executor.execute(
            agentId = AgentId.GRAPH,
            context = baseContext(),
            input = "hello",
            eventSink = eventSink,
            loadPendingHistory = { history },
        )

        val executedContext = agent.executedContexts.single()
        assertEquals("hello", executedContext.input)
        assertEquals("Base system prompt", executedContext.systemPrompt)
        assertSame(eventSink, executedContext.runtimeEventSink)
        assertSame(callback, agent.receivedCallback)
        assertEquals(history, agent.loadedHistory)
        assertEquals("assistant response", result.output)
        assertEquals("Base system prompt", result.context.systemPrompt)
    }

    @Test
    fun `executor normalizes unavailable agent IDs before cancellation`() {
        val agent = CapturingAgent()
        val executor = AgentExecutor(
            agentProvider = { id ->
                assertEquals(AgentId.SKILLS_GRAPH, id)
                agent
            },
            availableAgents = listOf(AgentId.SKILLS_GRAPH),
        )

        executor.cancel(AgentId.GRAPH)

        assertEquals(1, agent.cancelCount)
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

    private class CapturingAgent : Agent {
        val executedContexts = mutableListOf<AgentContext<String>>()
        var receivedCallback: GraphStepCallback? = null
        var mailbox: ActiveRunMailbox? = null
        var loadedHistory = emptyList<LLMRequest.Message>()
        var receivedInputs = emptyList<ActiveRunInput>()
        var cancelCount = 0

        override val stream: Flow<AgentStreamChunk> = emptyFlow()

        override suspend fun execute(
            context: AgentContext<String>,
            loadPendingHistory: suspend () -> List<LLMRequest.Message>,
            onActiveRunReady: suspend (ActiveRunMailbox) -> Unit,
            onStep: GraphStepCallback?,
        ): AgentExecutionResult {
            val activeMailbox = ActiveRunMailbox(loadPendingHistory)
            mailbox = activeMailbox
            onActiveRunReady(activeMailbox)
            loadedHistory = activeMailbox.loadHistoryAtBoundary()
            receivedInputs = activeMailbox.drain().orEmpty()
            executedContexts += context
            receivedCallback = onStep
            return AgentExecutionResult(
                output = "assistant response",
                context = context.copy(input = "assistant response"),
            )
        }

        override fun cancel() {
            cancelCount += 1
        }
    }
}
