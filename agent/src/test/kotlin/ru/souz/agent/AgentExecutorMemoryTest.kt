package ru.souz.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import ru.souz.agent.runtime.AgentRuntimeEvent
import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.agent.session.GraphSessionRepository
import ru.souz.agent.session.GraphSessionService
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentSettings
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.memory.CompletedTurnMemoryInput
import ru.souz.memory.ConversationMemoryRuntime
import ru.souz.paths.SouzPaths
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AgentExecutorMemoryTest {
    @Test
    fun `execute uses memory system prompt before graph execution`() = runTest {
        val events = mutableListOf<String>()
        val memoryRuntime = RecordingMemoryRuntime(
            augmentedPrompt = "Base system prompt\n\nRelevant memory:\n- Prefer Kotlin.",
            onBuild = { events += "memory.build" },
        )
        val agent = CapturingAgent(
            output = "assistant response",
            onExecute = { events += "agent.execute" },
        )
        val executor = AgentExecutor(
            agentProvider = { agent },
            memoryRuntime = memoryRuntime,
        )

        val result = executor.execute(
            agentId = AgentId.GRAPH,
            context = baseContext(),
            input = "hello",
        )

        assertEquals(listOf("memory.build", "agent.execute"), events)
        assertEquals("Base system prompt\n\nRelevant memory:\n- Prefer Kotlin.", agent.executedContexts.single().systemPrompt)
        assertEquals(
            "Base system prompt\n\nRelevant memory:\n- Prefer Kotlin.",
            agent.executedContexts.single().history.first().content,
        )
        assertEquals("Base system prompt", result.context.systemPrompt)
        assertEquals("Base system prompt", result.context.history.first().content)
    }

    @Test
    fun `execute falls back to base prompt when memory retrieval fails`() = runTest {
        val memoryRuntime = RecordingMemoryRuntime(
            buildFailure = IllegalStateException("retrieval failed"),
        )
        val agent = CapturingAgent(output = "assistant response")
        val executor = AgentExecutor(
            agentProvider = { agent },
            memoryRuntime = memoryRuntime,
        )

        val result = executor.execute(
            agentId = AgentId.GRAPH,
            context = baseContext(),
            input = "hello",
        )

        assertEquals("assistant response", result.output)
        assertEquals("Base system prompt", agent.executedContexts.single().systemPrompt)
        assertEquals("Base system prompt", agent.executedContexts.single().history.first().content)
    }

    @Test
    fun `execute captures completed turn after successful graph execution`() = runTest {
        val memoryRuntime = RecordingMemoryRuntime()
        val executor = AgentExecutor(
            agentProvider = { CapturingAgent(output = "assistant response") },
            memoryRuntime = memoryRuntime,
        )

        executor.execute(
            agentId = AgentId.GRAPH,
            context = baseContext(
                toolInvocationMeta = ToolInvocationMeta.localDefault(
                    conversationId = "conversation-1",
                    requestId = "request-1",
                    attributes = mapOf(
                        "userMessageId" to "user-message-1",
                        "assistantMessageId" to "assistant-message-1",
                    ),
                )
            ),
            input = "hello",
        )

        assertEquals(
            CompletedTurnMemoryInput(
                conversationId = "conversation-1",
                userMessageId = "user-message-1",
                assistantMessageId = "assistant-message-1",
                userMessage = "hello",
                assistantMessage = "assistant response",
            ),
            memoryRuntime.capturedTurns.single(),
        )
    }

    @Test
    fun `capture failure does not fail execution`() = runTest {
        val memoryRuntime = RecordingMemoryRuntime(
            captureFailure = IllegalStateException("capture failed"),
        )
        val executor = AgentExecutor(
            agentProvider = { CapturingAgent(output = "assistant response") },
            memoryRuntime = memoryRuntime,
        )

        val result = executor.execute(
            agentId = AgentId.GRAPH,
            context = baseContext(),
            input = "hello",
        )

        assertEquals("assistant response", result.output)
        assertEquals(1, memoryRuntime.capturedTurns.size)
    }

    @Test
    fun `memory prompt augmentation is emitted as runtime event`() = runTest {
        val memoryRuntime = RecordingMemoryRuntime(
            augmentedPrompt = "Base system prompt\n\nRelevant memory:\n- Prefer Kotlin.",
        )
        val runtimeEvents = mutableListOf<AgentRuntimeEvent>()
        val executor = AgentExecutor(
            agentProvider = { CapturingAgent(output = "assistant response") },
            memoryRuntime = memoryRuntime,
        )

        executor.execute(
            agentId = AgentId.GRAPH,
            context = baseContext(),
            input = "hello",
            eventSink = CollectingEventSink(runtimeEvents),
        )

        val event = assertIs<AgentRuntimeEvent.MemoryPromptAugmented>(runtimeEvents.single())
        assertEquals("Relevant memory:\n- Prefer Kotlin.", event.addedBlock)
    }

    @Test
    fun `graph session service records memory prompt augmentation event`() = runTest {
        val tempRoot = kotlin.io.path.createTempDirectory("souz-agent-session-test")
        val repository = GraphSessionRepository(TestSouzPaths(tempRoot))
        val service = GraphSessionService(repository, restJsonMapper)

        service.startTask("hello")
        service.emit(AgentRuntimeEvent.MemoryPromptAugmented("Relevant memory:\n- Prefer Kotlin."))
        service.finishTask()

        val step = repository.loadAll().single().steps.single()
        assertEquals("Memory Prompt Augmentation", step.nodeName)
        assertEquals("Relevant memory:\n- Prefer Kotlin.", step.outputSummary)
        assertTrue(step.data.contains("Relevant memory:\\n- Prefer Kotlin."))
    }

    private fun baseContext(
        toolInvocationMeta: ToolInvocationMeta = ToolInvocationMeta.localDefault(),
    ): AgentContext<String> = AgentContext(
        input = "",
        settings = AgentSettings(
            model = "model",
            temperature = 0f,
            toolsByCategory = emptyMap(),
        ),
        history = listOf(LLMRequest.Message(LLMMessageRole.system, "Base system prompt")),
        activeTools = emptyList(),
        systemPrompt = "Base system prompt",
        toolInvocationMeta = toolInvocationMeta,
    )

    private class CapturingAgent(
        private val output: String,
        private val onExecute: () -> Unit = {},
    ) : TraceableAgent {
        val executedContexts = mutableListOf<AgentContext<String>>()

        override val sideEffects: Flow<String> = emptyFlow()

        override suspend fun execute(ctx: AgentContext<String>): String =
            executeWithTrace(ctx).output

        override suspend fun executeWithTrace(
            ctx: AgentContext<String>,
            onStep: GraphStepCallback?,
        ): AgentExecutionResult {
            onExecute()
            executedContexts += ctx
            return AgentExecutionResult(
                output = output,
                context = ctx.copy(
                    input = output,
                    history = ctx.history + LLMRequest.Message(LLMMessageRole.assistant, output),
                ),
            )
        }

        override fun cancelActiveJob() = Unit
    }

    private class RecordingMemoryRuntime(
        private val augmentedPrompt: String? = null,
        private val buildFailure: Throwable? = null,
        private val captureFailure: Throwable? = null,
        private val onBuild: () -> Unit = {},
    ) : ConversationMemoryRuntime {
        val capturedTurns = mutableListOf<CompletedTurnMemoryInput>()

        override suspend fun buildSystemPrompt(
            baseSystemPrompt: String,
            userMessage: String,
            conversationId: String?,
        ): String {
            onBuild()
            buildFailure?.let { throw it }
            return augmentedPrompt ?: baseSystemPrompt
        }

        override suspend fun captureCompletedTurn(input: CompletedTurnMemoryInput) {
            capturedTurns += input
            captureFailure?.let { throw it }
        }
    }

    private class CollectingEventSink(
        private val events: MutableList<AgentRuntimeEvent>,
    ) : AgentRuntimeEventSink {
        override suspend fun emit(event: AgentRuntimeEvent) {
            events += event
        }
    }

    private class TestSouzPaths(
        override val stateRoot: Path,
    ) : SouzPaths {
        override val sessionsDir: Path = stateRoot.resolve("sessions")
        override val vectorIndexDir: Path = stateRoot.resolve("vector-index")
        override val logsDir: Path = stateRoot.resolve("logs")
        override val modelsDir: Path = stateRoot.resolve("models")
        override val nativeLibsDir: Path = stateRoot.resolve("native")
        override val skillsDir: Path = stateRoot.resolve("skills")
        override val skillValidationsDir: Path = stateRoot.resolve("skill-validations")
    }
}
