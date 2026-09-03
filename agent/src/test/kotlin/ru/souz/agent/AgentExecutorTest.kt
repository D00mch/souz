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
    fun `executor normalizes and forwards execution state without proxying the mailbox`() = runTest {
        val agent = CapturingAgent()
        val selectedAgents = mutableListOf<AgentId>()
        val eventSink = object : AgentRuntimeEventSink {
            override suspend fun emit(event: AgentRuntimeEvent) = Unit
        }
        val callback: GraphStepCallback = { _, _, _, _ -> }
        val executor = AgentExecutor(
            agentProvider = { id ->
                selectedAgents += id
                agent
            },
            availableAgents = listOf(AgentId.SKILLS_GRAPH),
            onStep = callback,
        )
        val input = ActiveRunInput(
            history = listOf(LLMRequest.Message(LLMMessageRole.assistant, "client answer")),
            input = "continue",
        )
        val history = listOf(LLMRequest.Message(LLMMessageRole.user, "pending history"))
        var readyMailbox: ActiveRunMailbox? = null

        val result = executor.execute(
            agentId = AgentId.GRAPH,
            context = baseContext(),
            input = "hello",
            eventSink = eventSink,
            loadPendingHistory = { history },
            onActiveRunReady = { mailbox ->
                readyMailbox = mailbox
                assertTrue(mailbox.submit { input })
            },
        )

        assertSame(agent.mailbox, readyMailbox)
        assertEquals(listOf(input), agent.receivedInputs)
        val executedContext = agent.executedContexts.single()
        assertEquals("hello", executedContext.input)
        assertEquals("Base system prompt", executedContext.systemPrompt)
        assertSame(eventSink, executedContext.runtimeEventSink)
        assertSame(callback, agent.receivedCallback)
        assertEquals(history, agent.loadedHistory)
        assertEquals("assistant response", result.output)
        assertEquals("Base system prompt", result.context.systemPrompt)

        executor.cancel(AgentId.GRAPH)

        assertEquals(listOf(AgentId.SKILLS_GRAPH, AgentId.SKILLS_GRAPH), selectedAgents)
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
