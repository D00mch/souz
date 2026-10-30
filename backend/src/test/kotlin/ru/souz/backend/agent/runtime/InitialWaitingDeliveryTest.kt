package ru.souz.backend.agent.runtime

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import ru.souz.agent.runtime.AgentRuntimeEvent
import ru.souz.backend.agent.model.AgentConversationKey
import ru.souz.backend.agent.model.BackendConversationTurnRequest
import ru.souz.backend.agent.session.AgentConversationSession
import ru.souz.backend.client.ClientThreadRuntimeRegistry
import ru.souz.backend.events.bus.AgentEventBus
import ru.souz.backend.events.model.AgentEvent
import ru.souz.backend.events.model.AgentEventType
import ru.souz.backend.events.model.AssistantMessagePayload
import ru.souz.backend.events.repository.AgentEventRepository
import ru.souz.backend.events.service.AgentEventService
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.backend.execution.repository.AgentExecutionRepository
import ru.souz.backend.execution.repository.CommittedAgentTurn
import ru.souz.backend.execution.service.AgentExecutionFinalizer
import ru.souz.backend.toolcall.repository.ToolCallRepository
import ru.souz.llms.LLMResponse

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class InitialWaitingDeliveryTest {
    @Test
    fun `finalizer publishes the answer before joining stalled waiting cleanup outside graph scopes`() = runTest {
        val fixture = Fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { fixture.repository.latestSeq(any(), any()) } coAnswers {
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            0L
        }
        val completed = terminal(AgentExecutionStatus.COMPLETED)
        val executions = mockk<AgentExecutionRepository> {
            coEvery { commitTurn(any(), any(), any(), any(), any(), any()) } returns
                CommittedAgentTurn(completed, mockk(relaxed = true), true)
        }
        val runner = mockk<BackendConversationTurnRunner> {
            coEvery { run(any(), any(), any(), any()) } coAnswers {
                // A graph scope must finish even while the waiting lookup is still pending.
                coroutineScope {
                    fixture.sink.emit(AgentRuntimeEvent.InitialWait())
                    entered.await()
                    BackendConversationTurnOutcome.Completed("answer", LLMResponse.Usage(0, 0, 0, 0),
                        AgentConversationSession(emptyList(), 0.5f, "ru", "UTC"))
                }
            }
        }
        val finalizer = AgentExecutionFinalizer(executions, runner, ClientThreadRuntimeRegistry("test"))
        val subscription = fixture.bus.subscribe("user", fixture.chat)
        val execution = launch {
            finalizer.runExecution(completed, AgentConversationKey.fromChat("user", fixture.chat),
                BackendConversationTurnRequest("request", "test-model", locale = "ru", timeZone = "UTC"), fixture.sink)
        }
        try {
            assertEquals(AgentEventType.THREAD_COMPLETED, subscription.events.receive().type)
            assertTrue(!execution.isCompleted, "The execution still owns pending cleanup after delivering the answer")
        } finally {
            release.complete(Unit)
        }
        execution.join()
        assertTrue(subscription.events.tryReceive().isFailure)
        subscription.close()
    }

    @Test
    fun `waiting is scheduled once and intermediate settings gate both waiting and narration`() = runTest {
        for (enabled in listOf(false, true)) for (narrate in listOf(false, true)) {
            val fixture = Fixture(this, enabled, narrate)
            val subscription = fixture.bus.subscribe("user", fixture.chat)
            List(16) {
                async(Dispatchers.Default) { fixture.sink.emit(AgentRuntimeEvent.InitialWait("travel")) }
            }.awaitAll()
            runCurrent()
            if (enabled) assertEquals("travel wait", (subscription.events.receive().payload as AssistantMessagePayload).content)
            fixture.sink.emit(AgentRuntimeEvent.AssistantMessage("working"))
            if (enabled && narrate) assertEquals("working", (subscription.events.receive().payload as AssistantMessagePayload).content)
            fixture.sink.stopInitialWait()
            assertTrue(subscription.events.tryReceive().isFailure)
            subscription.close()
        }
    }

    @Test
    fun `cancelling event processing releases serialization so terminal delivery can finish`() = runTest {
        val fixture = Fixture(this)
        val entered = CompletableDeferred<Unit>()
        coEvery { fixture.toolCalls.started(any(), any(), any(), any()) } coAnswers {
            entered.complete(Unit)
            awaitCancellation()
        }
        val subscription = fixture.bus.subscribe("user", fixture.chat)
        val processing = launch {
            fixture.sink.emit(AgentRuntimeEvent.ToolCallStarted("tool", "GetSkillByName", emptyMap()))
        }
        entered.await()
        val finishing = launch { fixture.sink.emitTerminal(terminal(AgentExecutionStatus.COMPLETED)) }
        runCurrent()
        processing.cancelAndJoin()
        finishing.join()

        assertEquals(AgentEventType.THREAD_COMPLETED, subscription.events.receive().type)
        fixture.sink.emit(AgentRuntimeEvent.InitialWait())
        runCurrent()
        assertTrue(subscription.events.tryReceive().isFailure)
        subscription.close()
    }

    @Test
    fun `terminal publication and progress overtake a database lookup even when it ignores cancellation`() = runTest {
        for (overtake in listOf("completed", "failed", "cancelled", "narration", "tool")) {
            val fixture = Fixture(this)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            coEvery { fixture.repository.latestSeq(any(), any()) } coAnswers {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
                0L
            }
            val subscription = fixture.bus.subscribe("user", fixture.chat)
            fixture.sink.emit(AgentRuntimeEvent.InitialWait())
            entered.await()
            // Narration has its own sequence lookup and must not use the stalled waiting lookup.
            coEvery { fixture.repository.latestSeq(any(), any()) } returns 0L
            try {
                when (overtake) {
                    "narration" -> fixture.sink.emit(AgentRuntimeEvent.AssistantMessage("working"))
                    "tool" -> fixture.sink.emit(AgentRuntimeEvent.ToolCallStarted("tool", "GetSkillByName", emptyMap()))
                    else -> fixture.sink.emitTerminal(terminal(when (overtake) {
                        "failed" -> AgentExecutionStatus.FAILED
                        "cancelled" -> AgentExecutionStatus.CANCELLED
                        else -> AgentExecutionStatus.COMPLETED
                    }))
                }
                if (overtake != "tool") {
                    val delivered = subscription.events.receive()
                    assertEquals(if (overtake == "narration") AgentEventType.ASSISTANT_MESSAGE
                        else AgentEventType.entries.single { it.value == "thread.$overtake" }, delivered.type)
                }
                assertTrue(!release.isCompleted, "Publication must not await the pending database lookup")
            } finally {
                release.complete(Unit)
            }
            runCurrent()
            fixture.sink.emit(AgentRuntimeEvent.InitialWait())
            runCurrent()
            assertTrue(subscription.events.tryReceive().isFailure, overtake)
            subscription.close()
        }
    }

    @Test
    fun `delivery failures do not cancel execution and execution cancellation stops delivery`() = runTest {
        val failed = Fixture(this)
        coEvery { failed.repository.latestSeq(any(), any()) } throws IllegalStateException("database unavailable")
        failed.sink.emit(AgentRuntimeEvent.InitialWait())
        runCurrent()
        failed.sink.emitTerminal(terminal(AgentExecutionStatus.COMPLETED))
        coVerify(exactly = 1) { failed.repository.append(any(), any(), any(), AgentEventType.THREAD_COMPLETED, any(), any(), any()) }

        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val owner = launch {
            coroutineScope {
                val fixture = Fixture(this)
                coEvery { fixture.repository.latestSeq(any(), any()) } coAnswers {
                    entered.complete(Unit)
                    try { CompletableDeferred<Unit>().await(); 0L } finally { cancelled.complete(Unit) }
                }
                fixture.sink.emit(AgentRuntimeEvent.InitialWait())
            }
        }
        entered.await()
        owner.cancelAndJoin()
        assertTrue(cancelled.isCompleted)
    }

    private fun terminal(status: AgentExecutionStatus): AgentExecution = mockk(relaxed = true) {
        every { this@mockk.status } returns status
        every { assistantMessageId } returns null
    }

    private class Fixture(scope: CoroutineScope? = null, enabled: Boolean = true, narrate: Boolean = true) {
        val chat = UUID.randomUUID()
        val repository = mockk<AgentEventRepository> {
            coEvery { latestSeq(any(), any()) } returns 0L
            coEvery { append(any(), any(), any(), any(), any(), any(), any()) } answers {
                AgentEvent(arg(5), arg(0), arg(1), arg(2), 1L, arg(3), arg(4), arg(6))
            }
        }
        val bus = AgentEventBus()
        val toolCalls = mockk<ToolCallRepository>(relaxed = true)
        val sink = BackendAgentRuntimeEventSink(
            "user", chat, UUID.randomUUID(), mockk(), mockk(), AgentEventService(mockk(), repository, bus),
            toolCalls, streamingMessagesEnabled = true, toolEventsEnabled = true,
            publicClientThread = true, narrateSteps = narrate, intermediateMessages = enabled,
            waitingPhrases = WaitingPhrases(listOf("generic wait"), mapOf("travel" to WaitingPhrases.Category("Trips", listOf("travel wait")))),
        ).also { if (scope != null) it.attachWaitingScope(scope) }
    }
}
